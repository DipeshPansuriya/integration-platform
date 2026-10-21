package io.integrationplatform.routing.adapter.db;

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
 */
public final class JdbcDbSender implements DbSender {

    public static final String DELIVERY_ID_COLUMN = "delivery_id";

    private final Duration timeout;

    public JdbcDbSender(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public void insert(DbMessage message, UUID deliveryId) throws SQLException {
        DbSettings settings = message.settings();
        Properties account = new Properties();
        account.setProperty("user", settings.user() == null ? settings.database() : settings.user());
        if (message.password() != null) {
            account.setProperty("password", new String(message.password()));
        }
        int seconds = Math.max(1, (int) timeout.toSeconds());
        account.setProperty("connectTimeout", String.valueOf(seconds));
        account.setProperty("socketTimeout", String.valueOf(seconds));
        String url = "jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database();
        try (Connection connection = DriverManager.getConnection(url, account)) {
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

    /** The parameterised INSERT: validated plain identifiers, every data value a statement parameter. */
    static String insert(DbMessage message) {
        String names = message.columns().stream().collect(Collectors.joining(", ")) + ", " + DELIVERY_ID_COLUMN;
        String parameters = java.util.stream.Stream
                .generate(() -> "?").limit(message.columns().size() + 1).collect(Collectors.joining(", "));
        return "INSERT INTO " + message.table() + " (" + names + ") VALUES (" + parameters
                + ") ON CONFLICT (" + DELIVERY_ID_COLUMN + ") DO NOTHING";
    }
}