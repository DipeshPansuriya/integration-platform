package io.integrationplatform.app.scheduler;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * T198 (B35, ADR-012): db-scheduler swallows a failed start-up task ("Unexpected error while executing OnStartup
 * tasks. Continuing."), so a missing scheduler grant leaves the context up with an empty job register. This check
 * compares what SHOULD be registered - a recurring task whose {@code job_definition} row is active - with the
 * distinct {@code task_name} values db-scheduler holds in {@code scheduler.scheduled_tasks}, read through the
 * {@link SchedulerClient}. Platform-wide: no tenant is read or reported.
 *
 * <p>Fail closed, OUT_OF_SERVICE when the scheduler has not started, nothing is expected, a job type is missing, or
 * either table cannot be read. The readiness group includes it (config-secrets.yml), liveness never does: a restart
 * cannot fix a missing grant. The probe re-reads every time and has no side effects (it never triggers a sync). The
 * details carry job type names or a fixed reason only - never SQL, an exception message, a schema or a login.
 */
public final class SchedulerJobsHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulerJobsHealthIndicator.class);

    private final List<String> recurringJobTypes;
    private final JobDefinitionRepository repository;
    private final SchedulerClient client;
    private final SchedulerState state;
    private final AtomicBoolean ready = new AtomicBoolean(true);

    public SchedulerJobsHealthIndicator(List<Task<?>> tasks, JobDefinitionRepository repository,
            SchedulerClient client, SchedulerState state) {
        this.recurringJobTypes = tasks.stream().filter(RecurringTask.class::isInstance).map(Task::getName)
                .distinct().toList();
        this.repository = repository;
        this.client = client;
        this.state = state;
    }

    @Override
    public Health health() {
        if (!state.isStarted()) {
            return notReady(Health.status(Status.OUT_OF_SERVICE).withDetail("reason", "starting").build(), false);
        }
        Set<String> expected;
        Set<String> actual;
        try {
            expected = recurringJobTypes.stream()
                    .filter(type -> repository.find(type).filter(JobDefinitionRow::active).isPresent())
                    .collect(Collectors.toCollection(TreeSet::new));
            actual = client.getScheduledExecutions().stream().map(ScheduledExecution::getTaskInstance)
                    .map(instance -> instance.getTaskName()).collect(Collectors.toSet());
        } catch (RuntimeException unreadable) {
            return notReady(Health.status(Status.OUT_OF_SERVICE).withDetail("reason", "job register unreadable")
                    .build(), true);
        }
        if (expected.isEmpty()) {
            return notReady(Health.status(Status.OUT_OF_SERVICE).withDetail("reason", "no active job type").build(),
                    true);
        }
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            return notReady(Health.status(Status.OUT_OF_SERVICE).withDetail("missingJobTypes", List.copyOf(missing))
                    .build(), true);
        }
        ready.set(true);
        return Health.up().build();
    }

    /** One error line per transition to not-ready (not per probe); start-up warm-up (not started) stays quiet. */
    private Health notReady(Health health, boolean alert) {
        if (alert && ready.getAndSet(false)) {
            LOG.atError()
                    .addKeyValue("event", "scheduler.instance_not_ready")
                    .addKeyValue("alert", true)
                    .log("event=scheduler.instance_not_ready alert=true - the job register is incomplete or"
                            + " unreadable, readiness fails until every active job type is registered");
        }
        return health;
    }
}
