package io.integrationplatform.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.integrationplatform.IntegrationPlatformApplication;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenario "Two instances never run one job twice" (@negative, E14-F2-S1, T-058): given two application instances and
 * 20 due jobs, when both wake at once, then each job runs exactly once and no scheduler state lives outside the
 * database. Both instances are real application contexts of platform-app over one real PostgreSQL container (ADR-012
 * fixes claiming to the database: the suite's instances are two Spring contexts in this same JVM). The two instances
 * wake on their own polling and race to claim the 20 rows through db-scheduler's row locking; the proof observes the
 * claimed rows live (picked=true, picked_by naming the instance, last_heartbeat refreshed) and then the exactly-once
 * record. "No scheduler state outside the database" is proven against the catalog: the only scheduled-state table in
 * the whole database is scheduler.scheduled_tasks, with exactly the seam's column shape (picked / picked_by /
 * last_heartbeat / last_success / last_failure / consecutive_failures rows only). Container group (R03): ./mvnw
 * -Pcontainer, one Docker suite at a time.
 */
@Tag("container")
class TwoInstanceClaimingContainerTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    static final int DUE_JOBS = 20;

    /** The seam's exact scheduled_tasks shape (V2_2_1400): the scheduler's claimed-state rows, nothing more. */
    static final String EXPECTED_COLUMNS = "task_name,task_instance,task_data,execution_time,picked,picked_by,"
            + "last_success,last_failure,consecutive_failures,last_heartbeat,version,priority";

    private static ConfigurableApplicationContext bootstrap;
    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    @BeforeAll
    static void startTwoInstances() throws Exception {
        POSTGRES.start();
        Map<String, Object> wiring = SchedulerProofSupport.wiringProperties(POSTGRES);
        bootstrap = instanceOf(wiring, "e14.scheduler-proof", "true", "db-scheduler.enabled", "false");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login(wiring));
        SchedulerProofSupport.seedJobDefinition(POSTGRES, SchedulerProofTasks.PROOF_CLAIM, 1);
        bootstrap.close();

        instanceA = instanceOf(wiring,
                "e14.scheduler-proof", "true",
                "db-scheduler.table-name", SchedulerProofSupport.SCHEDULED_TASKS,
                "db-scheduler.polling-interval", SchedulerProofSupport.POLLING_INTERVAL,
                "db-scheduler.heartbeat-interval", "2s",
                "db-scheduler.threads", "8",
                "db-scheduler.scheduler-name", "instance-a");
        instanceB = instanceOf(wiring,
                "e14.scheduler-proof", "true",
                "db-scheduler.table-name", SchedulerProofSupport.SCHEDULED_TASKS,
                "db-scheduler.polling-interval", SchedulerProofSupport.POLLING_INTERVAL,
                "db-scheduler.heartbeat-interval", "2s",
                "db-scheduler.threads", "8",
                "db-scheduler.scheduler-name", "instance-b");
    }

    @AfterAll
    static void stopBothInstances() {
        if (instanceA != null) {
            instanceA.close();
        }
        if (instanceB != null) {
            instanceB.close();
        }
        POSTGRES.stop();
    }

    @Test
    void twentyDueJobsRunExactlyOnceAcrossBothInstances() throws Exception {
        Scheduler schedulerOfA = instanceA.getBean(Scheduler.class);

        // "Given two application instances and 20 due jobs": both instances are already awake; the rows fall due
        // at one shared instant so the next polls of both instances race to claim them
        Instant due = Instant.now().plusSeconds(2);
        for (int job = 1; job <= DUE_JOBS; job++) {
            schedulerOfA.schedule(new TaskInstance<>(SchedulerProofTasks.PROOF_CLAIM, "job-" + job, null), due);
        }

        // the claimed rows are observable live: picked=true with the claiming instance named, heartbeats refreshed
        Set<String> claimers = ConcurrentHashMap.newKeySet();
        Set<String> claimedWithHeartbeat = ConcurrentHashMap.newKeySet();
        SchedulerProofSupport.pollUntil(() -> {
            sampleClaimedRows(claimers, claimedWithHeartbeat);
            return SchedulerProofTasks.totalRunsOf(SchedulerProofTasks.PROOF_CLAIM) == DUE_JOBS;
        }, Duration.ofSeconds(60));

        // "then each job runs exactly once"
        for (int job = 1; job <= DUE_JOBS; job++) {
            assertThat(SchedulerProofTasks.runsOf(SchedulerProofTasks.PROOF_CLAIM, "job-" + job))
                    .as("job-" + job + " ran exactly once").isEqualTo(1);
        }
        assertThat(claimers).as("both instances claimed different due jobs")
                .containsExactlyInAnyOrder("instance-a", "instance-b");
        assertThat(claimedWithHeartbeat).as("claimed executions kept a last_heartbeat in the database")
                .isNotEmpty();

        // "and no scheduler state lives outside the database": the only scheduled-state table in the whole
        // database is scheduler.scheduled_tasks, with exactly the seam's claimed-state column shape
        assertThat(SchedulerProofSupport.countRows(POSTGRES,
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE (table_name LIKE '%scheduled%' OR table_name LIKE '%scheduler%')"
                        + " AND NOT (table_schema = 'scheduler' AND table_name = 'scheduled_tasks')"))
                .as("no other table in the database holds scheduler state").isEqualTo(0);
        assertThat(SchedulerProofSupport.singleText(POSTGRES,
                "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns"
                        + " WHERE table_schema = 'scheduler' AND table_name = 'scheduled_tasks'"))
                .as("scheduler.scheduled_tasks carries the seam's claimed-state rows only")
                .isEqualTo(EXPECTED_COLUMNS);

        // after the race there is no residue: every claimed one-time row completed and was removed
        assertThat(SchedulerProofSupport.countRows(POSTGRES,
                "SELECT count(*) FROM " + SchedulerProofSupport.SCHEDULED_TASKS
                        + " WHERE task_name = ?", SchedulerProofTasks.PROOF_CLAIM))
                .as("no claimed execution row remains after the 20 jobs completed")
                .isEqualTo(0);
    }

    private static void sampleClaimedRows(Set<String> claimers, Set<String> claimedWithHeartbeat) {
        try (Connection connection = SchedulerProofSupport.ownerConnection(POSTGRES);
                PreparedStatement rows = connection.prepareStatement(
                        "SELECT DISTINCT picked_by FROM " + SchedulerProofSupport.SCHEDULED_TASKS + " WHERE picked");
                PreparedStatement heartbeats = connection.prepareStatement(
                        "SELECT DISTINCT picked_by FROM " + SchedulerProofSupport.SCHEDULED_TASKS
                                + " WHERE picked AND last_heartbeat IS NOT NULL")) {
            try (ResultSet rs = rows.executeQuery()) {
                while (rs.next()) {
                    claimers.add(rs.getString(1));
                }
            }
            try (ResultSet rs = heartbeats.executeQuery()) {
                while (rs.next()) {
                    claimedWithHeartbeat.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException pollFailure) {
            // a sampling miss must never fail the suite; the exactly-once assertions below are the hard gate
        }
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