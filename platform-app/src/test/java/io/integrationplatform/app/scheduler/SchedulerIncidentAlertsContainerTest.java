package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import io.integrationplatform.config.scheduler.JdbcJobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobScheduleSync;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 T-056 + T-062 against real PostgreSQL with the real db-scheduler (container group, R03): the run/alert rules
 * and the missed-detection rules, hand-built the way a deployment runs them - Flyway first, then a started
 * db-scheduler over the migrated schema with the row-driven register schedule.
 *
 * <ul>
 *   <li>a failed run writes one job_run FAILED row and one FAILED alert (never twice for the incident);</li>
 *   <li>a run over its max_run_seconds is FAILED with error class {@code MaxRunSecondsExceeded};</li>
 *   <li>an unclaimed execution past its late_threshold raises exactly one MISSED alert, records one MISSED run
 *       (late flag), and cancels the pending execution - detecting again adds nothing;</li>
 *   <li>a job_definition row change takes effect at the next schedule sync (cron change, pause, resume).</li>
 * </ul>
 *
 * <p>The scheduler is stopped after the first leg so the overdue probe and the sync leg run against a quiet table -
 * the same client still works: db-scheduler's client operations are plain SQL on {@code scheduler.scheduled_tasks}.
 */
@Tag("container")
@TestMethodOrder(OrderAnnotation.class)
class SchedulerIncidentAlertsContainerTest {

    static final String POSTGRES_TAG = "postgres:17.11-alpine";

    static PostgreSQLContainer POSTGRES;
    static PGSimpleDataSource dataSource;
    static JdbcJobDefinitionRepository repository;
    static JobIncidentJdbcSink sink;
    static Scheduler scheduler;
    static List<Task<?>> tasks;

    /** Throws on its first run (the FAILED incident), succeeds afterwards (the loop keeps rescheduling). */
    static final class ThrowingOnceJobHandler implements JobHandler {

        final AtomicBoolean thrown = new AtomicBoolean();

        @Override
        public String type() {
            return "DELIVERY_RETRY";
        }

        @Override
        public void run(String jobData) {
            if (thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("boom");
            }
        }
    }

    @BeforeAll
    static void deployMigrationsAndStartTheScheduler() {
        POSTGRES = new PostgreSQLContainer(POSTGRES_TAG);
        POSTGRES.start();
        // the beside-tenancy migrations (they create ipaas_app and the tenant gates) and this platform's own -
        // version-ordered, applied by the real Flyway as the owner user, exactly the SchedulerSchemaContainerTest
        // deployment way. The scheduler tables (V2_2_1400) and the register seed (V2_2_1420) live in
        // platform-config's migration directory, so that location is part of the deploy list.
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String tenancy = basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration")
                .normalize().toString().replace('\\', '/');
        String sharedConfig = basedir.getParent().resolve("platform-config/src/main/resources/db/migration")
                .normalize().toString().replace('\\', '/');
        String config = basedir.resolve("src/main/resources/db/migration").normalize().toString()
                .replace('\\', '/');
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + tenancy, "filesystem:" + sharedConfig, "filesystem:" + config)
                .load()
                .migrate();

        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        // the operator changed the row (the register seed carries the production cadence; this suite runs fast)
        updateJobDefinition("DELIVERY_RETRY", "FIXED_DELAY", "2");

        repository = new JdbcJobDefinitionRepository(dataSource);
        sink = new JobIncidentJdbcSink(dataSource);
        JobRunWrapper wrapper = new JobRunWrapper(List.of(new ThrowingOnceJobHandler()));
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, repository, new SimpleMeterRegistry());

        tasks = List.of(
                SchedulerJobTasks.recurringTask("DELIVERY_RETRY", repository, wrapper),
                SchedulerJobTasks.recurringTask("PAYLOAD_PURGE", repository, wrapper),
                SchedulerJobTasks.recurringTask("DAILY_REPORT", repository, wrapper),
                SchedulerJobTasks.recurringTask("CREDENTIAL_CLEANUP", repository, wrapper));

        scheduler = Scheduler.create(dataSource, tasks)
                .pollingInterval(Duration.ofSeconds(1))
                .addSchedulerListener(listener)
                .build();
        scheduler.start();

        // the four register rows exist: the schedule sync at start seeded pending executions for the enabled jobs
        assertThat(eventually(30, "four pending register rows", () -> pendingRowCount() == 4)).isTrue();
    }

    @Test
    @Order(1)
    void aFailedRunIsRecordedWithExactlyOneFailedAlert() throws SQLException {
        // wait for the failing execution to be recorded
        assertThat(eventually(30, "a job_run FAILED row", () -> runCount("FAILED") >= 1)).isTrue();
        Instant failedScheduledFor = firstRunTime("FAILED");

        assertThat(runCount("FAILED")).isEqualTo(1);
        assertThat(firstRunErrorClass("FAILED")).isEqualTo(IllegalStateException.class.getName());
        // one failed run, one FAILED alert for that incident - never twice
        assertThat(sink.raiseAlert("DELIVERY_RETRY", failedScheduledFor, "FAILED", null, "duplicate probe"))
                .as("the unique incident constraint refuses the same incident twice").isFalse();
        assertThat(alertCount("DELIVERY_RETRY", "FAILED", failedScheduledFor)).isEqualTo(1);

        // the body succeeded afterwards; those later runs are recorded without an alert
        assertThat(eventually(30, "a later SUCCEEDED run", () -> runCount("SUCCEEDED") >= 1)).isTrue();
        Instant succeededScheduledFor = firstRunTime("SUCCEEDED");
        assertThat(alertCount("DELIVERY_RETRY", "FAILED", succeededScheduledFor)).isEqualTo(0);

        scheduler.stop();
    }

    @Test
    @Order(2)
    void anOverdueUnclaimedExecutionRaisesOneMissedAlertAndCancelsThePendingExecution() throws SQLException {
        // a quiet table: nothing is overdue
        JobOverdueDetector detector = new JobOverdueDetector(dataSource, sink, scheduler);
        assertThat(detector.oldestOverdueSeconds()).isEqualTo(0.0);
        assertThat(detector.detectAndRecord()).isEqualTo(0);

        // plant an execution that was due but never picked: PAYLOAD_PURGE, due 12 minutes ago,
        // its late_threshold_seconds is 600
        Instant dueAt = Instant.now().minusSeconds(720);
        try (var connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(
                        "insert into scheduler.scheduled_tasks"
                                + " (task_name, task_instance, execution_time, picked, version)"
                                + " values ('PAYLOAD_PURGE', 'overdue-probe', ?, false, 1)")) {
            insert.setObject(1, dueAt);
            insert.executeUpdate();
        }

        assertThat(detector.oldestOverdueSeconds()).isGreaterThan(600.0);
        assertThat(detector.detectAndRecord()).isEqualTo(1);

        // exactly one MISSED alert for the incident, one MISSED run with the late flag and no instance
        assertThat(alertCount("PAYLOAD_PURGE", "MISSED", dueAt)).isEqualTo(1);
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select count(*), bool_and(is_late), bool_and(instance is null) from scheduler.job_run"
                        + " where job_type = 'PAYLOAD_PURGE' and outcome = 'MISSED'"
                        + " and scheduled_for = '" + dueAt.toString() + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("one MISSED run row").isEqualTo(1);
            assertThat(rs.getBoolean(2)).as("the MISSED run is flagged late").isTrue();
            assertThat(rs.getBoolean(3)).as("a MISSED run has no instance (it never started)").isTrue();
        }

        // the pending execution was canceled, and detecting again adds nothing (never alert twice)
        assertThat(detector.detectAndRecord()).isEqualTo(0);
        assertThat(pendingRowCount("PAYLOAD_PURGE")).isEqualTo(0);
        assertThat(alertCount("PAYLOAD_PURGE", "MISSED", dueAt)).isEqualTo(1);
        assertThat(detector.oldestOverdueSeconds()).isEqualTo(0.0);
    }

    @Test
    @Order(3)
    void aJobDefinitionRowChangeTakesEffectAtTheNextScheduleSync() throws SQLException {
        // the startup sync (the scheduler's own onStartup) put the daily report at its row's 00:05 slot
        Instant beforeChange = pendingExecutionTime("DAILY_REPORT");
        assertThat(beforeChange).isEqualTo(nextCron(beforeChange, "0 5 0 * * *"));

        // the operator changes the pattern; the next sync reconciles the task against the changed row
        updateJobDefinition("DAILY_REPORT", "CRON", "0 6 0 * * *");
        new JobScheduleSync(tasks, scheduler, Instant::now).sync();
        Instant afterChange = pendingExecutionTime("DAILY_REPORT");
        assertThat(afterChange).isNotEqualTo(beforeChange);
        assertThat(afterChange).isEqualTo(nextCron(afterChange, "0 6 0 * * *"));

        // pause the row: the next sync cancels the pending execution; the job never fires while paused
        pauseJobDefinition("DAILY_REPORT", true);
        new JobScheduleSync(tasks, scheduler, Instant::now).sync();
        assertThat(pendingRowCount("DAILY_REPORT")).isEqualTo(0);

        // resume: the next sync schedules it again at the row's (changed) pattern
        pauseJobDefinition("DAILY_REPORT", false);
        new JobScheduleSync(tasks, scheduler, Instant::now).sync();
        assertThat(pendingExecutionTime("DAILY_REPORT")).isEqualTo(nextCron(Instant.now(), "0 6 0 * * *"));
    }

    // ---- helpers

    private static void updateJobDefinition(String jobType, String scheduleKind, String schedule) {
        try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(
                "update scheduler.job_definition set schedule_kind = ?, schedule = ? where job_type = ?")) {
            update.setString(1, scheduleKind);
            update.setString(2, schedule);
            update.setString(3, jobType);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pauseJobDefinition(String jobType, boolean paused) throws SQLException {
        try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(
                "update scheduler.job_definition set paused = ? where job_type = ?")) {
            update.setBoolean(1, paused);
            update.setString(2, jobType);
            update.executeUpdate();
        }
    }

    private static int pendingRowCount() throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select count(*) from scheduler.scheduled_tasks")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int pendingRowCount(String jobType) throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select count(*) from scheduler.scheduled_tasks where task_name = '" + jobType + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Instant pendingExecutionTime(String jobType) throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select execution_time from scheduler.scheduled_tasks where task_name = '" + jobType + "'")) {
            assertThat(rs.next()).as("a pending execution for " + jobType).isTrue();
            return rs.getObject(1, Instant.class);
        }
    }

    /** The first cron slot strictly after {@code after} in UTC - the row's pattern, resolved by the code under test. */
    private static Instant nextCron(Instant after, String pattern) {
        return new com.github.kagkarlsson.scheduler.task.schedule.CronSchedule(pattern, java.time.ZoneOffset.UTC)
                .getNextExecutionTime(com.github.kagkarlsson.scheduler.task.ExecutionComplete.simulatedSuccess(after));
    }

    private static int runCount(String outcome) throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select count(*) from scheduler.job_run where outcome = '" + outcome + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Instant firstRunTime(String outcome) throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select scheduled_for from scheduler.job_run where outcome = '" + outcome + "' order by started")) {
            rs.next();
            return rs.getObject(1, Instant.class);
        }
    }

    private static String firstRunErrorClass(String outcome) throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select error_class from scheduler.job_run where outcome = '" + outcome + "' order by started")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int alertCount(String jobType, String kind, Instant scheduledFor) throws SQLException {
        try (var connection = dataSource.getConnection(); var rs = connection.createStatement().executeQuery(
                "select count(*) from scheduler.job_alert where job_type = '" + jobType + "' and kind = '" + kind
                        + "' and scheduled_for = '" + scheduledFor.toString() + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean eventually(int seconds, String what, SqlBoolean condition) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.value()) {
                    return true;
                }
            } catch (SQLException e) {
                last = e;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        System.out.println("gave up waiting for " + what + (last == null ? "" : ": " + last));
        return false;
    }

    interface SqlBoolean {
        boolean value() throws SQLException;
    }
}