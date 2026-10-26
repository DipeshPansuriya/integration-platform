package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.internal.persistence.JdbcSchedulerRegistry;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 follow-up: {@code scheduler.job_definition.paused_at} is read through the reviewed crossing into
 * {@link JobDefinitionRow#pausedAt()}, written by the same crossing's {@code setPaused}. Container group (R03);
 * needs only PostgreSQL. Modelled on {@link JobRunSearchContainerTest}.
 */
@Tag("container")
class JobDefinitionPausedAtContainerTest {

    private static final Instant AT = Instant.parse("2026-10-19T09:00:00Z");

    private static PostgreSQLContainer postgres;
    private static SchedulerRegistry registry;
    private static JdbcJobDefinitionRepository definitions;

    @BeforeAll
    static void deployMigrations() {
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
        registry = new JdbcSchedulerRegistry(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        definitions = new JdbcJobDefinitionRepository(registry);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void pausedAtIsReadBackWhilePausedAndNullAfterResume() {
        assertThat(definitions.find("DAILY_REPORT")).get().extracting(JobDefinitionRow::pausedAt).isNull();

        registry.setPaused("DAILY_REPORT", true, "ops-agent", AT);

        assertThat(definitions.find("DAILY_REPORT")).get().satisfies(row -> {
            assertThat(row.paused()).isTrue();
            assertThat(row.pausedAt()).isEqualTo(AT);
        });
        List<JobDefinitionRow> all = definitions.all();
        assertThat(all).filteredOn(row -> row.pausedAt() != null).extracting(JobDefinitionRow::jobType)
                .containsExactly("DAILY_REPORT");

        registry.setPaused("DAILY_REPORT", false, "ops-agent", AT.plusSeconds(60));

        assertThat(definitions.find("DAILY_REPORT")).get().satisfies(row -> {
            assertThat(row.paused()).isFalse();
            assertThat(row.pausedAt()).isNull();
        });
    }
}
