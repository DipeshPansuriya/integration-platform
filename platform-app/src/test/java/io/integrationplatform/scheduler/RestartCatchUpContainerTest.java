package io.integrationplatform.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.IntegrationPlatformApplication;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenario "A restart loses nothing" (E14-F2-S1, T-057): given a job due while all instances were down, when an
 * instance starts, then the missed run fires once and is recorded as late. The downtime is real: a first application
 * context with the scheduler disabled runs only the migrations and grants the restricted login its role; while nothing
 * can claim work, the due execution row is written into scheduler.scheduled_tasks exactly the way a previous
 * instance's schedule would have left it (picked=false, due in the past). The restarting instance then claims the
 * stale row through db-scheduler's database-guaranteed claiming and runs it exactly once. "Recorded as late" is
 * proven on the recording surface the production run wrapper (lane B's T-056) will own: the proof writes one
 * scheduler.job_run row through the restricted application login with scheduled_for = the missed due instant and
 * is_late computed against it, then reads it back the way the run search does. Container group (R03): ./mvnw
 * -Pcontainer, one Docker suite at a time.
 */
@Tag("container")
class RestartCatchUpContainerTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    static final String INSTANCE_ID = "missed-run-1";
    static final String SCHEDULER_NAME = "instance-b";

    private static ConfigurableApplicationContext downtime;
    private static ConfigurableApplicationContext restartingInstance;
    private static Instant missedRunDue;

    @BeforeAll
    static void downtimeThenRestart() throws Exception {
        POSTGRES.start();
        Map<String, Object> wiring = SchedulerProofSupport.wiringProperties(POSTGRES);

        // all instances down: migrations run, the login is granted, and nothing can claim work
        downtime = instanceOf(wiring, "e14.scheduler-proof", "true", "db-scheduler.enabled", "false");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login(wiring));
        SchedulerProofSupport.seedJobDefinition(POSTGRES, SchedulerProofTasks.PROOF_RESTART, 1);

        // the job fell due while no instance was up: its row is already in the scheduler's table
        missedRunDue = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        SchedulerProofTasks.MISSED_RUN_DUE.set(missedRunDue);         // handler reads it at execution time
        SchedulerProofTasks.INSTANCE_NAME.set(SCHEDULER_NAME);
        try (PreparedStatement insert = SchedulerProofSupport.ownerConnection(POSTGRES).prepareStatement(
                "INSERT INTO " + SchedulerProofSupport.SCHEDULED_TASKS
                        + " (task_name, task_instance, task_data, execution_time, picked, version)"
                        + " VALUES (?, ?, NULL, ?, false, 1)")) {
            insert.setString(1, SchedulerProofTasks.PROOF_RESTART);
            insert.setString(2, INSTANCE_ID);
            insert.setTimestamp(3, Timestamp.from(missedRunDue));
            insert.executeUpdate();
        }
        downtime.close(); // the restart is a real later start of a fresh instance

        restartingInstance = instanceOf(wiring,
                "e14.scheduler-proof", "true",
                "db-scheduler.table-name", SchedulerProofSupport.SCHEDULED_TASKS,
                "db-scheduler.polling-interval", SchedulerProofSupport.POLLING_INTERVAL,
                "db-scheduler.threads", "4",
                "db-scheduler.scheduler-name", SCHEDULER_NAME);
    }

    @AfterAll
    static void stopRestartingInstance() {
        if (restartingInstance != null) {
            restartingInstance.close();
        }
        POSTGRES.stop();
    }

    @Test
    void theMissedRunFiresOnceOnRestartAndIsRecordedLate() throws Exception {
        Instant instanceStarted = Instant.now();
        SchedulerProofSupport.pollUntil(
                () -> SchedulerProofTasks.runsOf(SchedulerProofTasks.PROOF_RESTART, INSTANCE_ID) == 1,
                Duration.ofSeconds(20));
        Instant firedAt = SchedulerProofTasks.firstFireOf(SchedulerProofTasks.PROOF_RESTART, INSTANCE_ID);

        assertThat(Duration.between(instanceStarted, firedAt))
                .as("the missed run fired on the instance's start (first polls)")
                .isLessThanOrEqualTo(Duration.ofSeconds(10));

        // "fires once" - exactly one execution in this JVM, and no second claiming on the shared row
        assertThat(SchedulerProofTasks.totalRunsOf(SchedulerProofTasks.PROOF_RESTART))
                .as("the missed run fired exactly once").isEqualTo(1);

        // "recorded as late" - one job_run row with the late flag, searchable by job type and outcome
        assertThat(SchedulerProofSupport.countRows(POSTGRES,
                "SELECT count(*) FROM scheduler.job_run"
                        + " WHERE job_type = ? AND outcome = 'SUCCEEDED' AND is_late = true"
                        + " AND scheduled_for = ?",
                SchedulerProofTasks.PROOF_RESTART, Timestamp.from(missedRunDue)))
                .as("the run is recorded with is_late=true and its missed scheduled_for")
                .isEqualTo(1);
        assertThat(SchedulerProofSupport.singleText(POSTGRES,
                "SELECT instance FROM scheduler.job_run WHERE job_type = ?",
                SchedulerProofTasks.PROOF_RESTART))
                .as("the run names the instance that finally ran it")
                .isEqualTo(SCHEDULER_NAME);

        // the claimed execution row is gone again; nothing was left behind by the catch-up
        assertThat(SchedulerProofSupport.countRows(POSTGRES,
                "SELECT count(*) FROM " + SchedulerProofSupport.SCHEDULED_TASKS + " WHERE task_name = ?",
                SchedulerProofTasks.PROOF_RESTART))
                .as("no execution row remains after the catch-up ran")
                .isEqualTo(0);
    }

    private static ConfigurableApplicationContext instanceOf(Map<String, Object> properties, String... more) {
        return new SpringApplicationBuilder(IntegrationPlatformApplication.class)
                .sources(IntegrationPlatformApplication.class, SchedulerProofTasks.class)
                .properties(properties)
                .properties(more)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
    }

    private static PlatformTestWiring.RestrictedLogin login(Map<String, Object> properties) {
        return new PlatformTestWiring.RestrictedLogin(
                (String) properties.get("DB_USERNAME"), (String) properties.get("DB_PASSWORD"));
    }
}