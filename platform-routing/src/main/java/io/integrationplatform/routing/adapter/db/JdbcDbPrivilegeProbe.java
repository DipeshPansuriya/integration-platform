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
 * and never the password - then the catalogue question ({@code information_schema.table_privileges}) about the exact
 * target table with the account, schema and table as statement parameters. The password is wiped when the probe is
 * over; it is never logged.
 */
public class JdbcDbPrivilegeProbe implements DbPrivilegeProbe {

    /**
     * The account's granted privileges on one table, read from the catalogue: grantee, schema and table are
     * statement parameters (never concatenated names). The table schema is the insert path's default (the send
     * inserts through the search path's default schema).
     */
    static final String PRIVILEGES_SQL = "select privilege_type from information_schema.table_privileges"
            + " where grantee = ? and table_schema = ? and table_name = ? order by privilege_type";

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
                    settings.sslmode() == null ? defaultSslmode : settings.sslmode(), timeout);
            try (Connection connection = DriverManager.getConnection(JdbcDbSender.url(settings), account)) {
                if (settings.table() == null) {
                    return List.of(); // the target names no table: the credentialed connect is the whole probe
                }
                try (PreparedStatement statement = connection.prepareStatement(PRIVILEGES_SQL)) {
                    statement.setString(1, settings.user());
                    statement.setString(2, DEFAULT_SCHEMA);
                    statement.setString(3, settings.table());
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