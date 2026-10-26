package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * T198 (B35, ADR-012): the scheduler readiness check compares the job types that SHOULD be registered (a recurring
 * task with an active job_definition row) with the task names db-scheduler really holds in scheduler.scheduled_tasks.
 * Fails closed; the details name job types only.
 *
 * <pre>
 * Scenario: A missing scheduler grant is visible to the readiness probe
 *   Given the app login cannot write scheduler.scheduled_tasks, so no job was registered
 *   When the health probe runs
 *   Then the instance reports OUT_OF_SERVICE and names only the missing job types
 * </pre>
 */
class SchedulerJobsHealthIndicatorTest {

    private static final List<String> TYPES = List.of(SchedulerJobTasks.DELIVERY_RETRY,
            SchedulerJobTasks.PAYLOAD_PURGE, SchedulerJobTasks.DAILY_REPORT, SchedulerJobTasks.CREDENTIAL_CLEANUP);

    private final Map<String, JobDefinitionRow> rows = new java.util.HashMap<>();
    private final List<String> registered = new ArrayList<>();
    private RuntimeException repositoryFault;
    private RuntimeException clientFault;
    private boolean started = true;

    @BeforeEach
    void allFourActiveAndRegistered() {
        for (String type : TYPES) {
            rows.put(type, new JobDefinitionRow(type, "FIXED_DELAY", "30", true, false, 300, 60, null));
            registered.add(type);
        }
    }

    @Test
    void everyActiveJobTypeRegisteredIsUp() {
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aMissingJobTypeIsOutOfServiceAndNamesExactlyThatType() {
        registered.remove(SchedulerJobTasks.PAYLOAD_PURGE);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsOnlyKeys("missingJobTypes");
        assertThat(health.getDetails().get("missingJobTypes")).isEqualTo(List.of("PAYLOAD_PURGE"));
    }

    @Test
    void noActiveJobDefinitionRowIsOutOfServiceNeverUp() {
        rows.clear();
        registered.clear();

        assertThat(indicator().health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void aPausedRowIsNotExpectedSoItsAbsenceStaysUp() {
        rows.put(SchedulerJobTasks.DAILY_REPORT, new JobDefinitionRow(SchedulerJobTasks.DAILY_REPORT, "CRON",
                "0 5 0 * * *", true, true, 1800, 600, null));
        registered.remove(SchedulerJobTasks.DAILY_REPORT);

        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void anUnreadableRegisterIsOutOfServiceWithoutTheExceptionText() {
        repositoryFault = new IllegalStateException("permission denied for schema scheduler SECRET-SQL");

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsOnlyKeys("reason");
        assertThat(health.getDetails().toString()).doesNotContain("permission denied").doesNotContain("SECRET-SQL");
    }

    @Test
    void anUnreadableTaskTableIsOutOfServiceWithoutTheExceptionText() {
        clientFault = new IllegalStateException("permission denied for table scheduled_tasks SECRET-SQL");

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsOnlyKeys("reason");
        assertThat(health.getDetails().toString()).doesNotContain("permission denied").doesNotContain("SECRET-SQL");
    }

    @Test
    void aSchedulerThatHasNotStartedIsOutOfServiceStarting() {
        started = false;

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).isEqualTo(Map.of("reason", "starting"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SchedulerJobsHealthIndicator indicator() {
        JobDefinitionRepository repository = new JobDefinitionRepository() {
            @Override
            public Optional<JobDefinitionRow> find(String jobType) {
                if (repositoryFault != null) {
                    throw repositoryFault;
                }
                return Optional.ofNullable(rows.get(jobType));
            }

            @Override
            public List<JobDefinitionRow> all() {
                return List.copyOf(rows.values());
            }
        };
        SchedulerClient client = mock(SchedulerClient.class);
        if (clientFault != null) {
            when(client.getScheduledExecutions()).thenThrow(clientFault);
        } else {
            List executions = new ArrayList<>();
            for (String name : registered) {
                ScheduledExecution<Object> execution = mock(ScheduledExecution.class);
                when(execution.getTaskInstance()).thenReturn(new TaskInstance<>(name, "recurring"));
                executions.add(execution);
            }
            when(client.getScheduledExecutions()).thenReturn(executions);
        }
        SchedulerState state = mock(SchedulerState.class);
        when(state.isStarted()).thenReturn(started);
        List<Task<?>> tasks = new ArrayList<>();
        TYPES.forEach(type -> tasks.add(SchedulerJobTasks.recurringTask(type, repository, null)));
        return new SchedulerJobsHealthIndicator(tasks, repository, client, state);
    }
}
