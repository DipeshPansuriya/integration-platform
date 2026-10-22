package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.config.destination.PinnedSocketFactory;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * T-149: the real DB send - one JDBC connection to the target's database as its restricted account, the
 * parameterised INSERT with the delivery id for the {@code delivery_id} column, connect and socket timeouts bounded
 * - so an ambiguous commit never double-inserts on retry (E16-C4): the pre-state check skips a delivery id that is
 * already stored (E16 leftovers: it also lets a multi-row delivery store every row, which
 * {@code ON CONFLICT (delivery_id) DO NOTHING} silently dropped). No reconnect: a failed attempt goes back to the
 * layer-1 route and the retry wait, never retried here. A natural {@code 08001} is a target that never answered; a
 * {@code 08006} is a channel that dropped after the statement may already have been sent.
 *
 * <p>Security (E16 F1/F2/F5): the URL is built from the validated {@link DbSettings} parts only and carries NO
 * query - every driver option travels in the {@link Properties} object, so no tenant text can add one. The driver
 * dials the guard-pinned address through {@link PinnedSocketFactory} (the host is never re-resolved) while the
 * original host name stays the TLS server name. TLS is always requested ({@code sslmode} {@code require} by default,
 * or the target's stronger choice); an operator may relax the default only through the constructor.
 *
 * <p>Operator note (target database): the restricted account needs INSERT <b>and SELECT</b> on the target table - the
 * pre-state check reads {@code delivery_id} before the insert - and nothing more (the test-connection answer flags a
 * WIDE account). Without SELECT the send is refused PERMANENT with SQL state 42501. Both the send and the privilege
 * probe pin {@code currentSchema=public}: the table must live in {@code public}.
 */
public final class JdbcDbSender implements DbSender {

    public static final String DELIVERY_ID_COLUMN = "delivery_id";

    /** The TLS mode used when a target names none; the operator's setting, never a tenant's. */
    public static final String DEFAULT_SSLMODE = "require";

    private final Duration timeout;
    private final String defaultSslmode;
    private final java.nio.file.Path sslRootCert;

    public JdbcDbSender(Duration timeout) {
        this(timeout, DEFAULT_SSLMODE);
    }

    /** @param defaultSslmode the operator's default TLS mode for a target that names none ({@code disable} only for a test database) */
    public JdbcDbSender(Duration timeout, String defaultSslmode) {
        this(timeout, defaultSslmode, null);
    }

    /**
     * @param sslRootCert R3: the operator's CA file (already validated by {@link DbTlsPolicy#rootCert}); handed to the
     *                    driver as {@code sslrootcert} when the effective mode verifies the server, null = driver default
     */
    public JdbcDbSender(Duration timeout, String defaultSslmode, java.nio.file.Path sslRootCert) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.defaultSslmode = Objects.requireNonNull(defaultSslmode, "defaultSslmode");
        this.sslRootCert = sslRootCert;
    }

    @Override
    public void insert(DbMessage message, UUID deliveryId, InetAddress pinnedAddress) throws SQLException {
        Objects.requireNonNull(pinnedAddress, "pinnedAddress");
        Properties account = properties(message, pinnedAddress);
        try (Connection connection = DriverManager.getConnection(url(message.settings()), account)) {
            insert(connection, message, deliveryId);
        }
    }

    /**
     * E16 leftovers (CWE-1288 class): a multi-row delivery's rows ALL carry the delivery id, so the old
     * {@code ON CONFLICT (delivery_id) DO NOTHING} silently kept only row 1 and still reported SUCCESS. Now every
     * row is inserted inside one transaction, after a pre-state check: a delivery id that is already stored is
     * skipped whole (an ambiguous-commit retry or any redelivery re-inserts nothing, so the redelivery stays
     * idempotent), and a fresh delivery stores EVERY row. A table that instead holds the one-row-per-delivery
     * {@code UNIQUE (delivery_id)} answer refuses a multi-row delivery loudly (a {@code 23xxx} SQLState: the whole
     * transaction rolls back), never drops rows silently.
     */
    void insert(Connection connection, DbMessage message, UUID deliveryId) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement check = connection.prepareStatement(selectDelivery(message.table()))) {
            check.setObject(1, deliveryId);
            try (java.sql.ResultSet answer = check.executeQuery()) {
                if (answer.next()) {
                    connection.commit(); // already stored: the delivery id holds its rows; idempotent redelivery
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(insert(message))) {
            int slots = message.columns().size() + 1;
            for (List<Object> row : message.values()) {
                int slot = 1;
                for (Object value : row) {
                    statement.setObject(slot++, value);
                }
                statement.setObject(slot, deliveryId);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    /** F2: host, port and database only - each already validated by {@link DbSettings}; never a query string. */
    static String url(DbSettings settings) {
        return "jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database();
    }

    /** F1/F2/F5: every driver option, set on the Properties object (never the URL). */
    Properties properties(DbMessage message, InetAddress pinnedAddress) {
        return accountProperties(message.user(), message.password(), pinnedAddress,
                message.settings().sslmode() == null ? defaultSslmode : message.settings().sslmode(), timeout,
                sslRootCert);
    }

    /** The one account Properties builder, shared with the test-connection privilege probe (task 11):
     * the probe wires no root cert of its own (null), the send path passes the operator's. */
    static Properties accountProperties(String user, char[] password, InetAddress pinnedAddress, String sslmode,
            Duration timeout, java.nio.file.Path sslRootCert) {
        Properties account = new Properties();
        account.setProperty("user", user);
        if (password != null) {
            account.setProperty("password", new String(password));
        } else {
            // R7 (CWE-522/1188): with no password the pgjdbc driver would silently read ~/.pgpass or PGPASSFILE on
            // this host and authenticate with an ambient credential; an EXPLICIT empty password means the driver
            // never consults them - the send fails loudly instead.
            account.setProperty("password", "");
        }
        int seconds = Math.max(1, (int) timeout.toSeconds());
        account.setProperty("connectTimeout", String.valueOf(seconds));
        account.setProperty("socketTimeout", String.valueOf(seconds));
        account.setProperty("socketFactory", PinnedSocketFactory.class.getName());
        account.setProperty(PinnedSocketFactory.PINNED_PROPERTY, pinnedAddress.getHostAddress());
        account.setProperty("sslmode", sslmode);
        // S3 (CWE-427): the unqualified INSERT and the probe both mean public.<table>; no search_path element of the
        // target account can redirect either
        account.setProperty("currentSchema", JdbcDbPrivilegeProbe.DEFAULT_SCHEMA);
        if (sslRootCert != null && (sslmode.equals("verify-ca") || sslmode.equals("verify-full"))) {
            account.setProperty("sslrootcert", sslRootCert.toString());
        }
        return account;
    }

    /** The pre-state check of the delivery id, as one parameter (E16 leftovers; the table is a validated identifier). */
    static String selectDelivery(String table) {
        return "SELECT 1 FROM " + table + " WHERE delivery_id = ? LIMIT 1";
    }

    /** The parameterised INSERT: validated plain identifiers, every data value a statement parameter. */
    static String insert(DbMessage message) {
        String names = message.columns().stream().collect(Collectors.joining(", ")) + ", " + DELIVERY_ID_COLUMN;
        String parameters = java.util.stream.Stream
                .generate(() -> "?").limit(message.columns().size() + 1).collect(Collectors.joining(", "));
        return "INSERT INTO " + message.table() + " (" + names + ") VALUES (" + parameters + ")";
    }
}
