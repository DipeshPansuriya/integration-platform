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
 * parameterised INSERT with the delivery id for the {@code delivery_id} column and {@code ON CONFLICT
 * (delivery_id) DO NOTHING}, connect and socket timeouts bounded - so an ambiguous commit never double-inserts on
 * retry (E16-C4). No reconnect: a failed attempt goes back to the layer-1 route and the retry wait, never retried
 * here. A natural {@code 08001} is a target that never answered; a {@code 08006} is a channel that dropped after
 * the statement may already have been sent.
 *
 * <p>Security (E16 F1/F2/F5): the URL is built from the validated {@link DbSettings} parts only and carries NO
 * query - every driver option travels in the {@link Properties} object, so no tenant text can add one. The driver
 * dials the guard-pinned address through {@link PinnedSocketFactory} (the host is never re-resolved) while the
 * original host name stays the TLS server name. TLS is always requested ({@code sslmode} {@code require} by default,
 * or the target's stronger choice); an operator may relax the default only through the constructor.
 */
public final class JdbcDbSender implements DbSender {

    public static final String DELIVERY_ID_COLUMN = "delivery_id";

    /** The TLS mode used when a target names none; the operator's setting, never a tenant's. */
    public static final String DEFAULT_SSLMODE = "require";

    private final Duration timeout;
    private final String defaultSslmode;

    public JdbcDbSender(Duration timeout) {
        this(timeout, DEFAULT_SSLMODE);
    }

    /** @param defaultSslmode the operator's default TLS mode for a target that names none ({@code disable} only for a test database) */
    public JdbcDbSender(Duration timeout, String defaultSslmode) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.defaultSslmode = Objects.requireNonNull(defaultSslmode, "defaultSslmode");
    }

    @Override
    public void insert(DbMessage message, UUID deliveryId, InetAddress pinnedAddress) throws SQLException {
        Objects.requireNonNull(pinnedAddress, "pinnedAddress");
        Properties account = properties(message, pinnedAddress);
        try (Connection connection = DriverManager.getConnection(url(message.settings()), account)) {
            connection.setAutoCommit(false);
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
    }

    /** F2: host, port and database only - each already validated by {@link DbSettings}; never a query string. */
    static String url(DbSettings settings) {
        return "jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database();
    }

    /** F1/F2/F5: every driver option, set on the Properties object (never the URL). */
    Properties properties(DbMessage message, InetAddress pinnedAddress) {
        Properties account = new Properties();
        account.setProperty("user", message.user());
        if (message.password() != null) {
            account.setProperty("password", new String(message.password()));
        }
        int seconds = Math.max(1, (int) timeout.toSeconds());
        account.setProperty("connectTimeout", String.valueOf(seconds));
        account.setProperty("socketTimeout", String.valueOf(seconds));
        account.setProperty("socketFactory", PinnedSocketFactory.class.getName());
        account.setProperty(PinnedSocketFactory.PINNED_PROPERTY, pinnedAddress.getHostAddress());
        String sslmode = message.settings().sslmode() == null ? defaultSslmode : message.settings().sslmode();
        account.setProperty("sslmode", sslmode);
        return account;
    }

    /** The parameterised INSERT: validated plain identifiers, every data value a statement parameter. */
    static String insert(DbMessage message) {
        String names = message.columns().stream().collect(Collectors.joining(", ")) + ", " + DELIVERY_ID_COLUMN;
        String parameters = java.util.stream.Stream
                .generate(() -> "?").limit(message.columns().size() + 1).collect(Collectors.joining(", "));
        return "INSERT INTO " + message.table() + " (" + names + ") VALUES (" + parameters
                + ") ON CONFLICT (" + DELIVERY_ID_COLUMN + ") DO NOTHING";
    }
}
