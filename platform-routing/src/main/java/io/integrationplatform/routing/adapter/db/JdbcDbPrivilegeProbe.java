package io.integrationplatform.routing.adapter.db;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * E16 F3 (design note task 11, Karan change 4) on the send path's dial ({@link JdbcDbSender}): ONE pinned,
 * credentialed JDBC connection - the driver dials the guard-pinned address through {@code PinnedSocketFactory} while
 * the host name stays the TLS server name, every driver option rides the Properties object, the URL carries no query
 * and never the password - then the effective-privilege question about the exact
 * target table with the schema and table as statement parameters. The password is wiped when the probe is
 * over; it is never logged.
 */
public class JdbcDbPrivilegeProbe implements DbPrivilegeProbe {

    /**
     * The predefined roles that hand a login far more than INSERT/SELECT on one table (T203 / T189-S1): server-side
     * program execution and file read/write (host takeover from the database), backend signalling, ownership of the
     * database, and blanket read/write of every table. Membership by {@code pg_has_role(.., 'member')} is true for a
     * direct, an inherited AND a NOINHERIT membership (the account can {@code SET ROLE} into it) - the conservative
     * reading. A role the server does not have (pg_read_all_data, pg_database_owner before 14) reads as not a member.
     * The token is the upper-cased role name.
     */
    static final List<String> WIDE_PREDEFINED_ROLES = List.of("pg_execute_server_program", "pg_write_server_files",
            "pg_read_server_files", "pg_signal_backend", "pg_database_owner", "pg_write_all_data", "pg_read_all_data");

    /** T203: the number of statement parameters of {@link #PRIVILEGES_SQL}: 8 table slots, the schema, then the
     * other-table read's schema and table. */
    static final int PARAMETER_COUNT = 11;

    /**
     * S2: the account's EFFECTIVE privileges, asked of the server (roles, PUBLIC and inheritance included), not the
     * grant text: table INSERT/SELECT/UPDATE/REFERENCES/DELETE/TRUNCATE/TRIGGER, BYPASSRLS, CREATE on the schema,
     * superuser. T189: INSERT, SELECT, UPDATE and REFERENCES are asked with {@code has_any_column_privilege}, which is
     * true for a table-level grant AND for a grant on any single column ({@code GRANT UPDATE (col)}), which
     * {@code has_table_privilege} ignores. Every name is a statement parameter. A table that does not resolve
     * (to_regclass is null) yields the row TABLE_MISSING, which the probe turns into a {@code TABLE_NOT_FOUND}
     * refusal - never an empty, "narrow"-looking list (T189).
     *
     * <p>T203 (T189-S1) widens the question beyond the one table. Anything it reports other than INSERT and SELECT
     * makes the approval refuse the account ({@code DB_ACCOUNT_TOO_WIDE}). The added tokens:
     * <ul>
     * <li>{@code PG_EXECUTE_SERVER_PROGRAM, PG_WRITE_SERVER_FILES, PG_READ_SERVER_FILES, PG_SIGNAL_BACKEND,
     *     PG_DATABASE_OWNER, PG_WRITE_ALL_DATA, PG_READ_ALL_DATA} - membership of that predefined role, direct or
     *     inherited ({@link #WIDE_PREDEFINED_ROLES}).</li>
     * <li>{@code CREATEROLE, CREATEDB, REPLICATION} - the role attributes (SUPERUSER and BYPASSRLS already were).</li>
     * <li>{@code DATABASE_CREATE} - CREATE on the connected database.</li>
     * <li>{@code ANY_SCHEMA_CREATE} - CREATE on any schema the account can also USE (the pg_temp and pg_toast
     *     schemas excluded); a CREATE without USAGE reaches nothing and is not reported.</li>
     * <li>{@code OTHER_TABLE_PRIVILEGES} - on any OTHER table, view, materialized view, foreign table or partition of
     *     the approved schema: DELETE, TRUNCATE, TRIGGER, or UPDATE / REFERENCES at table or column level. Held to the
     *     same rule as the approved table, so INSERT and SELECT on a sibling table stays narrow (an account may serve
     *     several approved tables).</li>
     * </ul>
     *
     * <p>Cost: one catalog statement per probe, inside the 5 s connect and 5 s socket timeouts and a statement
     * timeout of the same size, so a slow catalog is a refusal (DB_ACCOUNT_UNVERIFIED), never a hang. The other-table
     * read is one scan of pg_class filtered to the approved schema with up to five ACL checks per relation,
     * short-circuiting at the first hit; a narrow account (the common case) reads every relation of the schema once.
     * Measured: see {@code DbAccountWidthVerdictContainerTest#theOtherTableReadIsBoundedInALargeSchema}. The schema
     * list and the role list are a handful of rows.
     *
     * <p>What is NOT read (so a release may not claim it): function EXECUTE (PUBLIC holds it by default), sequence or
     * large-object rights, tables of schemas other than the approved one, TEMP on the database (PUBLIC holds it by
     * default), pg_* roles outside {@link #WIDE_PREDEFINED_ROLES} (pg_monitor, pg_read_all_settings, pg_checkpoint,
     * pg_create_subscription, ...), ownership of objects, and anything that happens after approval.
     *
     * <p>Design limit (T189): the width is read at approve time and at test-connection only. It is NOT re-checked at
     * send time - a grant added to the account after approval is not seen until the next approval or test.
     */
    static final String PRIVILEGES_SQL = "select p from (values"
            + " ('DELETE', coalesce(has_table_privilege(current_user, to_regclass(?), 'DELETE'), false)),"
            + " ('INSERT', coalesce(has_any_column_privilege(current_user, to_regclass(?), 'INSERT'), false)),"
            + " ('SELECT', coalesce(has_any_column_privilege(current_user, to_regclass(?), 'SELECT'), false)),"
            + " ('UPDATE', coalesce(has_any_column_privilege(current_user, to_regclass(?), 'UPDATE'), false)),"
            + " ('REFERENCES', coalesce(has_any_column_privilege(current_user, to_regclass(?), 'REFERENCES'), false)),"
            + " ('TRUNCATE', coalesce(has_table_privilege(current_user, to_regclass(?), 'TRUNCATE'), false)),"
            + " ('TRIGGER', coalesce(has_table_privilege(current_user, to_regclass(?), 'TRIGGER'), false)),"
            + " ('TABLE_MISSING', to_regclass(?) is null),"
            + " ('BYPASSRLS', coalesce((select rolbypassrls from pg_roles where rolname = current_user), false)),"
            + " ('SCHEMA_CREATE', has_schema_privilege(current_user, ?, 'CREATE')),"
            + " ('SUPERUSER', coalesce((select rolsuper from pg_roles where rolname = current_user), false)),"
            + " ('CREATEROLE', coalesce((select rolcreaterole from pg_roles where rolname = current_user), false)),"
            + " ('CREATEDB', coalesce((select rolcreatedb from pg_roles where rolname = current_user), false)),"
            + " ('REPLICATION', coalesce((select rolreplication from pg_roles where rolname = current_user), false)),"
            + " ('DATABASE_CREATE', has_database_privilege(current_user, current_database(), 'CREATE')),"
            + " ('ANY_SCHEMA_CREATE', exists (select 1 from pg_namespace n where n.nspname !~ '^pg_(temp|toast)'"
            + " and has_schema_privilege(current_user, n.oid, 'USAGE')"
            + " and has_schema_privilege(current_user, n.oid, 'CREATE'))),"
            + " ('OTHER_TABLE_PRIVILEGES', exists (select 1 from pg_class c"
            + " join pg_namespace s on s.oid = c.relnamespace"
            + " where s.nspname = ? and c.relkind in ('r', 'p', 'v', 'm', 'f')"
            + " and c.oid <> coalesce(to_regclass(?), 0::oid)"
            + " and (has_table_privilege(current_user, c.oid, 'DELETE')"
            + " or has_table_privilege(current_user, c.oid, 'TRUNCATE')"
            + " or has_table_privilege(current_user, c.oid, 'TRIGGER')"
            + " or has_any_column_privilege(current_user, c.oid, 'UPDATE')"
            + " or has_any_column_privilege(current_user, c.oid, 'REFERENCES'))))"
            + WIDE_PREDEFINED_ROLES.stream().map(role -> ", ('" + role.toUpperCase() + "', coalesce((select"
                    + " pg_has_role(current_user, oid, 'member') from pg_roles where rolname = '" + role + "'), false))")
                    .collect(Collectors.joining())
            + ") v(p, held) where held order by p";

    /** The number of leading statement parameters that name the table; the next one names the schema. */
    private static final int TABLE_SLOTS = 8;

    static final String DEFAULT_SCHEMA = "public";

    private final DbSystemCredentials credentials;
    private final String defaultSslmode;

    public JdbcDbPrivilegeProbe(DbSystemCredentials credentials, String defaultSslmode) {
        this.credentials = credentials;
        this.defaultSslmode = defaultSslmode;
    }

    @Override
    public List<String> probe(UUID systemId, DbSettings settings, InetAddress pinnedAddress, Duration timeout)
            throws IOException, ProbeRefused {
        if (settings.user() == null) {
            throw new ProbeRefused("NO_ACCOUNT",
                    "the DB settings carry no restricted account to probe with");
        }
        char[] password = credentials.passwordOf(systemId).orElseThrow(() -> new ProbeRefused("NO_CREDENTIAL",
                "the restricted account's password is not in the vault; store it for this target's endpoint"));
        try {
            Properties account = JdbcDbSender.accountProperties(settings.user(), password, pinnedAddress,
                    settings.sslmode() == null ? defaultSslmode : settings.sslmode(), timeout, null);
            try (Connection connection = DriverManager.getConnection(JdbcDbSender.url(settings), account)) {
                if (settings.table() == null) {
                    return List.of(); // the target names no table: the credentialed connect is the whole probe
                }
                try (PreparedStatement statement = connection.prepareStatement(PRIVILEGES_SQL)) {
                    String qualified = DEFAULT_SCHEMA + "." + settings.table(); // a validated plain identifier
                    for (int slot = 1; slot <= TABLE_SLOTS; slot++) {
                        statement.setString(slot, qualified);
                    }
                    statement.setString(TABLE_SLOTS + 1, DEFAULT_SCHEMA);
                    statement.setString(TABLE_SLOTS + 2, DEFAULT_SCHEMA); // T203: the other-table read's schema
                    statement.setString(TABLE_SLOTS + 3, qualified); // and the approved table it skips
                    statement.setQueryTimeout(Math.max(1, (int) timeout.toSeconds())); // T203: the server cancels too
                    try (ResultSet rows = statement.executeQuery()) {
                        List<String> privileges = new ArrayList<>();
                        while (rows.next()) {
                            privileges.add(rows.getString(1));
                        }
                        if (privileges.contains("TABLE_MISSING")) {
                            // T189: no privilege read of a table that does not resolve - the approval must not call it narrow
                            throw new ProbeRefused("TABLE_NOT_FOUND",
                                    "the table '" + settings.table() + "' does not exist in schema " + DEFAULT_SCHEMA
                                    + " (or the account cannot see it)");
                        }
                        return List.copyOf(privileges);
                    }
                }
            }
        } catch (SQLException refused) {
            if (unreachable(refused)) {
                throw new IOException("the target database did not answer");
            }
            throw refusedProbe(refused);
        } finally {
            java.util.Arrays.fill(password, '\0'); // the probe is over: the copy dies, the vault never changed
        }
    }

    /** The unit-testable mapping of a driver failure: 08* is a database that did not answer. */
    static boolean unreachable(SQLException refused) {
        String state = refused.getSQLState();
        return state != null && state.startsWith("08");
    }

    /** The unit-testable mapping of a driver failure: 28* a refused account, the rest an answer's DATABASE_ERROR. */
    static ProbeRefused refusedProbe(SQLException refused) {
        String state = refused.getSQLState();
        if (state != null && state.startsWith("28")) {
            return new ProbeRefused("AUTH_REFUSED", "the target database refused the account (" + state + ")");
        }
        return new ProbeRefused("DATABASE_ERROR", "the privilege read failed ("
                + (state == null ? "no SQL state" : state) + ")");
    }
}
