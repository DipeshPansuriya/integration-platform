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
     * S2: the account's EFFECTIVE privileges, asked of the server (roles, PUBLIC and inheritance included), not the
     * grant text: table INSERT/SELECT/UPDATE/REFERENCES/DELETE/TRUNCATE/TRIGGER, BYPASSRLS, CREATE on the schema,
     * superuser, membership of pg_write_all_data. T189: INSERT, SELECT, UPDATE and REFERENCES are asked with
     * {@code has_any_column_privilege}, which is true for a table-level grant AND for a grant on any single column
     * ({@code GRANT UPDATE (col)}), which {@code has_table_privilege} ignores. Every name is a statement parameter.
     * A table that does not resolve (to_regclass is null) yields the row TABLE_MISSING, which the probe turns into a
     * {@code TABLE_NOT_FOUND} refusal - never an empty, "narrow"-looking list (T189); a server without
     * pg_write_all_data (before 14) reads as not a member.
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
            + " ('PG_WRITE_ALL_DATA', coalesce((select pg_has_role(current_user, oid, 'member') from pg_roles"
            + " where rolname = 'pg_write_all_data'), false))"
            + ") v(p, held) where held order by p";

    /** The number of leading statement parameters that name the table; the last one names the schema. */
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