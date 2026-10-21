package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.internal.persistence.JdbcSchedulerRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 T-127: {@code operatorCredentialActive} against the real migrated {@code scheduler.operator_credential}
 * (V2_2_1400), queried as a restricted login inside the application role {@code ipaas_app} - not the container's
 * superuser - so the grant-only shape is proven to let the application read it. Fixtures are written by the owner.
 * Container group (R03); needs only PostgreSQL. Modelled on {@link SchedulerSchemaContainerTest}.
 */
@Tag("container")
class OperatorCredentialActiveContainerTest {

    static final String ISSUER = "https://idp.test/realms/platform";

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource appDataSource;
    private static JdbcSchedulerRegistry registry;
    private static String appUser;

    @BeforeAll
    static void deployMigrationsAndSeedCredentials() throws SQLException {
        postgres = new PostgreSQLContainer(SchedulerSchemaContainerTest.POSTGRES_TAG);
        postgres.start();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String tenancy = basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration")
                .normalize().toString().replace('\\', '/');
        String config = basedir.resolve("src/main/resources/db/migration").normalize().toString()
                .replace('\\', '/');
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + tenancy, "filesystem:" + config)
                .load()
                .migrate();

        long seed = System.nanoTime();
        appUser = "operator_app_" + Long.toUnsignedString(seed, 36);
        String appPassword = "app" + Long.toUnsignedString(seed + 1, 32);
        try (Connection owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement sql = owner.createStatement()) {
            sql.execute("CREATE ROLE " + appUser + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE"
                    + " IN ROLE " + SchedulerSchemaContainerTest.APPLICATION_ROLE + " PASSWORD '" + appPassword + "'");
            sql.execute("insert into scheduler.operator_credential (issuer, client_id, status) values"
                    + " ('" + ISSUER + "', 'ops-active', 'ACTIVE'),"
                    + " ('" + ISSUER + "', 'ops-revoked', 'REVOKED')");
        }
        appDataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), appUser, appPassword);
        registry = new JdbcSchedulerRegistry(appDataSource);
    }

    @Test
    void theQueryRunsAsTheRestrictedApplicationLoginNotTheSuperuser() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);
        assertThat(app.queryForObject("select current_user", String.class)).isEqualTo(appUser);
        assertThat(app.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class))
                .isFalse();
        assertThat(app.queryForObject("select pg_has_role(current_user, ?, 'MEMBER')", Boolean.class,
                SchedulerSchemaContainerTest.APPLICATION_ROLE)).isTrue();
    }

    @Test
    void anActiveCredentialIsActive() {
        assertThat(registry.operatorCredentialActive(ISSUER, "ops-active")).isTrue();
    }

    @Test
    void aRevokedCredentialIsNotActive() {
        assertThat(registry.operatorCredentialActive(ISSUER, "ops-revoked")).isFalse();
    }

    @Test
    void anUnknownClientOrIssuerIsNotActive() {
        assertThat(registry.operatorCredentialActive(ISSUER, "ops-unknown")).isFalse();
        assertThat(registry.operatorCredentialActive("https://idp.other/realms/platform", "ops-active")).isFalse();
    }

    @Test
    void aBlankOrNullIssuerOrClientIdIsNotActive() {
        assertThat(registry.operatorCredentialActive(null, "ops-active")).isFalse();
        assertThat(registry.operatorCredentialActive(" ", "ops-active")).isFalse();
        assertThat(registry.operatorCredentialActive(ISSUER, null)).isFalse();
        assertThat(registry.operatorCredentialActive(ISSUER, "")).isFalse();
    }
}
