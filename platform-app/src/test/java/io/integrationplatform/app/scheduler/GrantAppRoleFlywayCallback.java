package io.integrationplatform.app.scheduler;

import io.integrationplatform.PlatformTestWiring;
import java.sql.SQLException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 278 scheduler-wiring reds (sanjay-developer): a Flyway callback for ONE test context only
 * (registered by {@link SchedulerSpringWiringContainerTest}'s nested configuration, never by the shared
 * {@link PlatformTestWiring}). It closes the startup race the wiring test hit in g3: the context boots
 * Flyway (as the migration owner) and then the db-scheduler starter's startup reconcile, but the restricted
 * app login only joined {@code ipaas_app} in {@code @BeforeEach} - after the scheduler had already logged
 * "permission denied for schema scheduler ... Continuing." and left {@code scheduler.scheduled_tasks}
 * empty. A deployment grants the login its role before the app ever starts; this callback reproduces that
 * ordering inside the test: AFTER_MIGRATE runs as the migration finishes, strictly before any scheduler or
 * pool bean. It grants nothing else and is idempotent (membership re-grant is a no-op).
 */
public final class GrantAppRoleFlywayCallback implements Callback {

    private final PostgreSQLContainer postgres;
    private final PlatformTestWiring.RestrictedLogin login;

    public GrantAppRoleFlywayCallback(PostgreSQLContainer postgres, PlatformTestWiring.RestrictedLogin login) {
        this.postgres = postgres;
        this.login = login;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        // membership is granted on its own owner connection after the migration transaction has committed
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            PlatformTestWiring.grantApplicationRole(postgres, login);
        } catch (SQLException e) {
            throw new IllegalStateException("the app login could not join ipaas_app after migration", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "grantAppRoleBetweenFlywayAndScheduler";
    }
}
