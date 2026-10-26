package io.integrationplatform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * K1 end to end (ledger row 393; reproduces the hosting smoke test C1): the real application, started against a
 * real PostgreSQL with a database login that could step round row-level security, must refuse to start with the K1
 * message. The intended runtime login passing is {@link ApplicationStartsAgainstPostgresTest}. Container group (R03).
 * Each case gets its own database: Flyway's history table belongs to whoever migrated first.
 */
@Tag("container")
class DatabaseRoleGuardStartupContainerTest {

    private static final String OWNER_LOGIN = "k1_app_owner";

    private static Map<String, Object> wiring(PostgreSQLContainer postgres, String migrationUser, String migrationPassword,
            String appUser, String appPassword) {
        Map<String, Object> properties = new HashMap<>();
        PlatformTestWiring.register(properties::put, postgres);
        properties.put("DB_MIGRATION_USERNAME", migrationUser);
        properties.put("DB_MIGRATION_PASSWORD", migrationPassword);
        properties.put("DB_USERNAME", appUser);
        properties.put("DB_PASSWORD", appPassword);
        properties.put("server.port", "0");
        Map<String, Object> resolved = new HashMap<>();
        properties.forEach((name, value) ->
                resolved.put(name, value instanceof Supplier<?> supplier ? supplier.get() : value));
        return resolved;
    }

    private static void run(Map<String, Object> properties) {
        new SpringApplicationBuilder(IntegrationPlatformApplication.class).properties(properties).run().close();
    }

    @Test
    @DisplayName("the app logging in as the table owner (the hosting migrator, C1) does not start")
    void theTableOwnerLoginIsRefusedAtStartup() throws Exception {
        String password = UUID.randomUUID().toString().replace("-", "");
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestImages.POSTGRES)) {
            postgres.start();
            try (Connection admin = PlatformTestWiring.ownerConnection(postgres); Statement sql = admin.createStatement()) {
                // like the hosting migrator: a non-superuser that owns the database (so public) and may create roles
                sql.execute("CREATE ROLE " + OWNER_LOGIN + " LOGIN CREATEROLE PASSWORD '" + password + "'");
                sql.execute("ALTER DATABASE " + postgres.getDatabaseName() + " OWNER TO " + OWNER_LOGIN);
            }
            Map<String, Object> properties = wiring(postgres, OWNER_LOGIN, password, OWNER_LOGIN, password);

            assertThatThrownBy(() -> run(properties)).hasStackTraceContaining("must not own a tenant table");
        }
    }

    @Test
    @DisplayName("the app logging in as a superuser does not start")
    void aSuperuserLoginIsRefusedAtStartup() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestImages.POSTGRES)) {
            postgres.start();
            Map<String, Object> properties = wiring(
                    postgres, postgres.getUsername(), postgres.getPassword(), postgres.getUsername(), postgres.getPassword());

            assertThatThrownBy(() -> run(properties)).hasStackTraceContaining("must not be a superuser");
        }
    }
}
