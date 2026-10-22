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
     * grant text: table INSERT/SELECT/UPDATE/DELETE, CREATE on the schema, superuser, membership of
     * pg_write_all_data. Every name is a statement parameter; a table that does not exist reads as no privilege
     * (to_regclass is null) and a server without pg_write_all_data (before 14) as not a member.
     */
    static final String PRIVILEGES_SQL = "select p from (values"
            + " ('DELETE', coalesce(has_table_privilege(current_user, to_regclass(?), 'DELETE'), false)),"
            + " ('INSERT', coalesce(has_table_privilege(current_user, to_regclass(?), 'INSERT'), false)),"
            + " ('SELECT', coalesce(has_table_privilege(current_user, to_regclass(?), 'SELECT'), false)),"
            + " ('UPDATE', coalesce(has_table_privilege(current_user, to_regclass(?), 'UPDATE'), false)),"
            + " ('SCHEMA_CREATE', has_schema_privilege(current_user, ?, 'CREATE')),"
            + " ('SUPERUSER', coalesce((select rolsuper from pg_roles where rolname = current_user), false)),"
            + " ('PG_WRITE_ALL_DATA', coalesce((select pg_has_role(current_user, oid, 'member') from pg_roles"
            + " where rolname = 'pg_write_all_data'), false))"
            + ") v(p, held) where held order by p";

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
                    for (int slot = 1; slot <= 4; slot++) {
                        statement.setString(slot, qualified);
                    }
                    statement.setString(5, DEFAULT_SCHEMA);
                    try (ResultSet rows = statement.executeQuery()) {
                        List<String> privileges = new ArrayList<>();
                        while (rows.next()) {
                            privileges.add(rows.getString(1));
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