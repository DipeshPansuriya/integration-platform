package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.internal.persistence.JdbcSchedulerRegistry;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import io.integrationplatform.tenancy.port.SchedulerRegistry.JobRunEntry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 T-059 (E14-F2-S1, criterion E14-C9): every run's outcome is recorded and searchable. Runs are written through
 * the reviewed crossing's {@code recordRun} (the way the scheduler writes them) and read back through
 * {@link JobRunSearch} - the existing {@code scheduler.job_run} table (V2_2_1400), no new table, no tenant id.
 * Container group (R03); needs only PostgreSQL. Modelled on {@link SchedulerSchemaContainerTest}.
 *
 * <pre>
 * Scenario: Every run is recorded and searchable
 *   Given completed, failed and missed runs
 *   When an operator searches by job, outcome and date
 *   Then each run's outcome, start, end and error are listed
 * </pre>
 */
@Tag("container")
class JobRunSearchContainerTest {

    static final Instant DAY = Instant.parse("2026-10-17T00:00:00Z");
    static final Instant NEXT_DAY = DAY.plus(Duration.ofDays(1));
    static final String FAILURE = "java.io.UncheckedIOException";

    private static PostgreSQLContainer postgres;
    private static JobRunSearch search;

    @BeforeAll
    static void deployMigrationsAndRecordRuns() throws SQLException {
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

        SchedulerRegistry registry = new JdbcSchedulerRegistry(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        search = new JobRunSearch(registry);

        // the day's DAILY_REPORT runs: completed, failed, missed (a missed run never started - V2_2_1400 allows nulls)
        Instant completed = DAY.plus(Duration.ofHours(1));
        Instant failed = DAY.plus(Duration.ofHours(2));
        Instant missed = DAY.plus(Duration.ofHours(3));
        registry.recordRun(new JobRunEntry("DAILY_REPORT", completed, completed.plusSeconds(1),
                completed.plusSeconds(30), "app-1", "SUCCEEDED", false, null, null));
        registry.recordRun(new JobRunEntry("DAILY_REPORT", failed, failed.plusSeconds(1),
                failed.plusSeconds(5), "app-2", "FAILED", false, FAILURE, null));
        registry.recordRun(new JobRunEntry("DAILY_REPORT", missed, null, null, null, "MISSED", true, null, null));
        // noise the filters must drop: another job the same day, the same job the day before
        registry.recordRun(new JobRunEntry("PAYLOAD_PURGE", completed, completed, completed.plusSeconds(2),
                "app-1", "SUCCEEDED", false, null, null));
        Instant dayBefore = DAY.minus(Duration.ofHours(12));
        registry.recordRun(new JobRunEntry("DAILY_REPORT", dayBefore, dayBefore, dayBefore.plusSeconds(3),
                "app-1", "SUCCEEDED", false, null, null));
        // counts is jsonb; the search reads it back as text
        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement sql = admin.createStatement()) {
            sql.executeUpdate("update scheduler.job_run set counts = '{\"rows\": 12}'"
                    + " where job_type = 'DAILY_REPORT' and outcome = 'SUCCEEDED' and started >= '2026-10-17'");
        }
    }

    @Test
    void eachOutcomeIsListedWithItsStartEndAndErrorWhenSearchedByJobOutcomeAndDate() {
        List<JobRunView> succeeded = search.search("DAILY_REPORT", "SUCCEEDED", DAY, NEXT_DAY, null);
        assertThat(succeeded).singleElement().satisfies(run -> {
            assertThat(run.outcome()).isEqualTo("SUCCEEDED");
            assertThat(run.started()).isEqualTo(DAY.plus(Duration.ofHours(1)).plusSeconds(1));
            assertThat(run.finished()).isEqualTo(DAY.plus(Duration.ofHours(1)).plusSeconds(30));
            assertThat(run.errorClass()).isNull();
            assertThat(run.counts()).isEqualTo("{\"rows\": 12}");
        });

        List<JobRunView> failed = search.search("DAILY_REPORT", "FAILED", DAY, NEXT_DAY, null);
        assertThat(failed).singleElement().satisfies(run -> {
            assertThat(run.outcome()).isEqualTo("FAILED");
            assertThat(run.started()).isEqualTo(DAY.plus(Duration.ofHours(2)).plusSeconds(1));
            assertThat(run.finished()).isEqualTo(DAY.plus(Duration.ofHours(2)).plusSeconds(5));
            assertThat(run.errorClass()).isEqualTo(FAILURE);
        });

        List<JobRunView> missed = search.search("DAILY_REPORT", "MISSED", DAY, NEXT_DAY, null);
        assertThat(missed).singleElement().satisfies(run -> {
            assertThat(run.outcome()).isEqualTo("MISSED");
            assertThat(run.scheduledFor()).isEqualTo(DAY.plus(Duration.ofHours(3)));
            assertThat(run.started()).isNull();
            assertThat(run.finished()).isNull();
            assertThat(run.late()).isTrue();
        });
    }

    @Test
    void withoutAnOutcomeEveryRunOfTheJobInTheWindowIsListedNewestFirst() {
        assertThat(search.search("DAILY_REPORT", null, DAY, NEXT_DAY, null))
                .extracting(JobRunView::outcome)
                .containsExactly("MISSED", "FAILED", "SUCCEEDED");
    }

    @Test
    void theLimitCapsTheListAndNoFilterListsEveryJob() {
        assertThat(search.search(null, null, null, null, null)).hasSize(5);
        assertThat(search.search(null, null, null, null, 2)).hasSize(2);
    }
}
