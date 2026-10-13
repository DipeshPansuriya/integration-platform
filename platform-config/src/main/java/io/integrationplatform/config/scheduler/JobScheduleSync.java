package io.integrationplatform.config.scheduler;

import com.github.kagkarlsson.scheduler.Clock;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.OnStartup;
import com.github.kagkarlsson.scheduler.task.Task;
import java.util.List;

/**
 * E14 T-056 (ADR-012 Decision 2): the schedule sync point. db-scheduler already reconciles every recurring task
 * with its {@link com.github.kagkarlsson.scheduler.task.schedule.Schedule} at scheduler start (that IS the sync at
 * start); the same reconcile runs again here on operator demand - so a job_definition row change (schedule changed,
 * paused, resumed) takes effect at the next schedule sync, without a restart. Tasks without a schedule (one-time
 * tasks) carry no reconcile and are untouched.
 */
public final class JobScheduleSync {

    private final List<Task<?>> tasks;
    private final SchedulerClient client;
    private final Clock clock;

    public JobScheduleSync(List<Task<?>> tasks, SchedulerClient client, Clock clock) {
        this.tasks = List.copyOf(tasks);
        this.client = client;
        this.clock = clock;
    }

    /** Reconciles every registered recurring task against its row-driven schedule, now. */
    public void sync() {
        for (Task<?> task : tasks) {
            if (task instanceof OnStartup onStartup) {
                onStartup.onStartup(client, clock);
            }
        }
    }
}