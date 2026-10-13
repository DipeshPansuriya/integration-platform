package io.integrationplatform.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.integrationplatform.IntegrationPlatformApplication;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
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
 * Scenario "On-time firing" (E14-F2-S1, T-057): given a job row due at 10:00:00, when the time comes, then it fires
 * within the polling interval - proven with real PostgreSQL and the real db-scheduler engine started the way the
 * application starts it (the starter auto-configuration over the restricted application login; R07 names via
 * {@link PlatformTestWiring}). ADR-012 mapping: the job's definition row lives in scheduler.job_definition, its
 * claimed execution row in scheduler.scheduled_tasks ({@code picked=false} until a scheduler claims it, deleted again
 * after the one-time run completes). The definition row is seeded as a fixture; the firing is measured against the due
 * instant on this test's clock. Container group (R03): runs only with ./mvnw -Pcontainer, one Docker suite at a time.
 */
@Tag("container")
class OnTimeFiringContainerTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    static final String INSTANCE_ID = "due-1000";

    private static ConfigurableApplicationContext instance;

    /** A bootstrap phase that runs the migrations and grants the login its role, then stops. */
    private static ConfigurableApplicationContext bootstrap;

    @BeforeAll
    static void startContainerBootstrapThenInstance() throws Exception {
        POSTGRES.start();
        Map<String, Object> wiring = SchedulerProofSupport.wiringProperties(POSTGRES);
        bootstrap = instanceOf(wiring, "e14.scheduler-proof", "true", "db-scheduler.enabled", "false");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login(wiring));
        SchedulerProofSupport.seedJobDefinition(POSTGRES, SchedulerProofTasks.PROOF_ON_TIME, 1);
        bootstrap.close();
        instance = instanceOf(wiring,
                "e14.scheduler-proof", "true",
                "db-scheduler.table-name", SchedulerProofSupport.SCHEDULED_TASKS,
                "db-scheduler.polling-interval", SchedulerProofSupport.POLLING_INTERVAL,
                "db-scheduler.threads", "4",
                "db-scheduler.scheduler-name", "instance-proof");
    }

    @AfterAll
    static void stopInstance() {
        if (instance != null) {
            instance.close();
        }
        POSTGRES.stop();
    }

    @Test
    void aDueJobRunsOnceWithinThePollingInterval() throws Exception {
        Scheduler scheduler = instance.getBean(Scheduler.class);

        // "Given a job row due at 10:00:00" - a fixed due instant slightly ahead on this test's clock
        Instant due = Instant.now().plusSeconds(2);
        scheduler.schedule(new TaskInstance<>(SchedulerProofTasks.PROOF_ON_TIME, INSTANCE_ID, null), due);

        // the job's row lives in scheduler.scheduled_tasks, claimed execution state only (picked=false until due)
        assertThat(SchedulerProofSupport.countRows(POSTGRES,
                "SELECT count(*) FROM " + SchedulerProofSupport.SCHEDULED_TASKS
                        + " WHERE task_name = ? AND task_instance = ? AND picked = false"
                        + " AND execution_time >= ? AND execution_time <= ?",
                SchedulerProofTasks.PROOF_ON_TIME, INSTANCE_ID,
                java.sql.Timestamp.from(due.minusMillis(50)), java.sql.Timestamp.from(due.plusMillis(50))))
                .as("the due job's row sits in scheduler.scheduled_tasks before it fires")
                .isEqualTo(1);

        // "When the time comes, then it fires within the polling interval"
        SchedulerProofSupport.pollUntil(
                () -> SchedulerProofTasks.runsOf(SchedulerProofTasks.PROOF_ON_TIME, INSTANCE_ID) == 1,
                Duration.ofSeconds(20));
        Instant fired = SchedulerProofTasks.firstFireOf(SchedulerProofTasks.PROOF_ON_TIME, INSTANCE_ID);

        assertThat(fired).as("fired at or after its due instant (clock skew slack only)")
                .isAfterOrEqualTo(due.minusMillis(100));
        assertThat(Duration.between(due, fired))
                .as("fired within the 1s polling interval plus scheduling slack")
                .isLessThanOrEqualTo(Duration.ofMillis(1000 + 2500));

        // and the claimed execution row leaves no residue once the one-time run completed
        assertThat(SchedulerProofSupport.countRows(POSTGRES,
                "SELECT count(*) FROM " + SchedulerProofSupport.SCHEDULED_TASKS
                        + " WHERE task_name = ? AND task_instance = ?",
                SchedulerProofTasks.PROOF_ON_TIME, INSTANCE_ID))
                .as("the completed one-time execution row is gone from scheduler.scheduled_tasks")
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