package io.integrationplatform.app.flyway;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Pattern;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * T204: bounds how long any migration may WAIT for a lock. V2_4_1370 adds a CHECK ... NOT VALID to the partitioned
 * {@code delivery} table, which needs ACCESS EXCLUSIVE; queued behind a long transaction it would stand in the lock
 * queue and block every delivery behind it. With {@code lock_timeout} the migration instead fails fast with SQLSTATE
 * 55P03 ("lock_not_available"), rolls back, writes no history row, and is simply run again in a quiet window.
 *
 * <p>Why a callback and not {@code spring.flyway.init-sql}: this runs {@code set_config('lock_timeout', ?, true)}
 * (the same as {@code SET LOCAL}) inside each migration's own transaction, so it is scoped to that migration and gone
 * at commit - nothing leaks into the schema-history connection, into a non-transactional migration (CREATE INDEX
 * CONCURRENTLY must be free to wait out old transactions) or into the application. Migration files stay untouched
 * (their checksums are on origin/main). A migration's own {@code SET LOCAL lock_timeout} still wins.
 *
 * <p>The value is a bind parameter, never SQL text, and is checked at startup. {@code 0} means "do nothing" (the
 * database's own setting applies).
 */
final class MigrationLockTimeoutCallback implements Callback {

    static final String PROPERTY = "ipaas.flyway.lock-timeout";
    private static final Pattern DURATION = Pattern.compile("\\d+(ms|s|min)?");

    private final String timeout;

    MigrationLockTimeoutCallback(String timeout) {
        String value = timeout == null ? "" : timeout.strip();
        if (!DURATION.matcher(value).matches()) {
            throw new IllegalArgumentException(PROPERTY + " must be a PostgreSQL duration such as 5s, 500ms or 1min"
                    + " (0 = leave the database setting), but was '" + timeout + "'");
        }
        this.timeout = value;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE && !"0".equals(timeout);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true; // it must run inside the migration's transaction for the local setting to reach it
    }

    @Override
    public void handle(Event event, Context context) {
        try (PreparedStatement statement = context.getConnection().prepareStatement(
                "SELECT set_config('lock_timeout', ?, true)")) {
            statement.setString(1, timeout);
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("could not set lock_timeout for the migration", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "migrationLockTimeout";
    }
}
