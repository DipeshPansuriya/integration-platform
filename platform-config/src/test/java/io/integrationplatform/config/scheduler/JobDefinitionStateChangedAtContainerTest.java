package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.internal.persistence.JdbcSchedulerRegistry;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.nio.file.Path;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 state-change stamp (V2_4_1320): {@code scheduler.job_definition.state_changed_at} is NOT NULL, backfilled from
 * {@code paused_at} (else the migration time) and bumped by a BEFORE UPDATE trigger only when {@code (enabled,
 * paused)} actually changes - whichever writer (the registry crossing, a migration, manual SQL) makes the change.
 * Container group (R03); needs only PostgreSQL. The migration is first run to 1310 so a paused row exists to backfill.
 */
@Tag("container")
class JobDefinitionStateChangedAtContainerTest {

    private static final Instant OLD = Instant.parse("2000-01-01T00:00:00Z");

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;
    private static SchedulerRegistry registry;
    private static JdbcJobDefinitionRepository definitions;
    private static Instant beforeMigration;
    private static Instant afterMigration;

    @BeforeAll
    static void deployMigrations() {
        postgres = new PostgreSQLContainer(SchedulerSchemaContainerTest.POSTGRES_TAG);
        postgres.start();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String tenancy = basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration")
                .normalize().toString().replace('\\', '/');
        String config = basedir.resolve("src/main/resources/db/migration").normalize().toString()
                .replace('\\', '/');
        Flyway before = flyway(tenancy, config, "2.4.1310");
        before.migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // the seed rows are not paused; pause one the way the register did before the column existed
        jdbc.update("update scheduler.job_definition set paused = true, paused_by = 'ops-agent',"
                + " paused_at = ?::timestamptz where job_type = 'DAILY_REPORT'", "2026-10-19T09:00:00Z");

        beforeMigration = Instant.now();
        flyway(tenancy, config, null).migrate();
        afterMigration = Instant.now();

        registry = new JdbcSchedulerRegistry(dataSource);
        definitions = new JdbcJobDefinitionRepository(registry);
    }

    private static Flyway flyway(String tenancy, String config, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + tenancy, "filesystem:" + config);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    // ---- task 1: the column and its backfill -------------------------------------------------------------------

    @Test
    void theColumnIsNotNullTimestamptzWithADefaultOfNow() {
        assertThat(jdbc.queryForMap("select is_nullable, data_type, column_default from information_schema.columns"
                + " where table_schema = 'scheduler' and table_name = 'job_definition'"
                + " and column_name = 'state_changed_at'"))
                .containsEntry("is_nullable", "NO")
                .containsEntry("data_type", "timestamp with time zone")
                .hasEntrySatisfying("column_default", value -> assertThat(String.valueOf(value)).contains("now()"));
    }

    @Test
    void aPausedRowIsBackfilledFromPausedAtAndEveryOtherRowFromTheMigrationTime() {
        JobDefinitionRow paused = definitions.find("DAILY_REPORT").orElseThrow();
        assertThat(paused.stateChangedAt()).isEqualTo(Instant.parse("2026-10-19T09:00:00Z"));
        assertThat(paused.stateChangedAt()).isEqualTo(paused.pausedAt());

        assertThat(definitions.all()).filteredOn(row -> !"DAILY_REPORT".equals(row.jobType()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.stateChangedAt())
                        .isBetween(beforeMigration.minusSeconds(5), afterMigration.plusSeconds(5)));
    }

    // ---- task 2: the trigger matrix (6 cases), on a job with a known, old stamp -------------------------------

    private static void resetTo(boolean enabled, boolean paused) {
        jdbc.update("update scheduler.job_definition set enabled = ?, paused = ?, paused_by = null, paused_at = null"
                + " where job_type = 'PAYLOAD_PURGE'", enabled, paused);
        stampOld();
    }

    /** Only state_changed_at is in the SET list, so the trigger (UPDATE OF enabled, paused) does not fire. */
    private static void stampOld() {
        jdbc.update("update scheduler.job_definition set state_changed_at = ?::timestamptz"
                + " where job_type = 'PAYLOAD_PURGE'", OLD.toString());
    }

    private static Instant stamp() {
        return definitions.find("PAYLOAD_PURGE").orElseThrow().stateChangedAt();
    }

    @Test
    void disablingAnEnabledJobBumpsTheStamp() {
        resetTo(true, false);

        jdbc.update("update scheduler.job_definition set enabled = false where job_type = 'PAYLOAD_PURGE'");

        assertThat(stamp()).isAfter(OLD);
    }

    @Test
    void writingEnabledFalseOnADisabledJobDoesNotBump() {
        resetTo(false, false);

        jdbc.update("update scheduler.job_definition set enabled = false where job_type = 'PAYLOAD_PURGE'");

        assertThat(stamp()).isEqualTo(OLD);
    }

    @Test
    void pausingBumpsTheStamp() {
        resetTo(true, false);

        registry.setPaused("PAYLOAD_PURGE", true, "ops-agent", Instant.parse("2026-10-22T09:00:00Z"));

        assertThat(stamp()).isAfter(OLD);
    }

    @Test
    void rePausingAPausedJobDoesNotBumpEvenThoughPausedAtIsRewritten() {
        resetTo(true, true);

        registry.setPaused("PAYLOAD_PURGE", true, "other-agent", Instant.parse("2026-10-22T10:00:00Z"));

        assertThat(definitions.find("PAYLOAD_PURGE").orElseThrow().pausedAt())
                .isEqualTo(Instant.parse("2026-10-22T10:00:00Z"));
        assertThat(stamp()).isEqualTo(OLD);
    }

    @Test
    void resumingBumpsTheStampThoughPausedAtIsCleared() {
        resetTo(true, true);

        registry.setPaused("PAYLOAD_PURGE", false, "ops-agent", Instant.parse("2026-10-22T11:00:00Z"));

        JobDefinitionRow row = definitions.find("PAYLOAD_PURGE").orElseThrow();
        assertThat(row.pausedAt()).isNull();
        assertThat(row.stateChangedAt()).isAfter(OLD);
    }

    @Test
    void aScheduleOnlyUpdateDoesNotBump() {
        resetTo(true, false);

        jdbc.update("update scheduler.job_definition set schedule = '0 30 2 * * *', max_run_seconds = 1800"
                + " where job_type = 'PAYLOAD_PURGE'");

        assertThat(stamp()).isEqualTo(OLD);
    }

    @Test
    void theTriggerFunctionIsNotExecutableByPublicAndIsNotSecurityDefiner() {
        assertThat(jdbc.queryForObject("select prosecdef from pg_proc p join pg_namespace n on n.oid = p.pronamespace"
                + " where n.nspname = 'scheduler' and p.proname = 'job_definition_touch_state'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("select has_function_privilege('public',"
                + " 'scheduler.job_definition_touch_state()', 'EXECUTE')", Boolean.class)).isFalse();
    }
}
