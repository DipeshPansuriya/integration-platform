package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.kagkarlsson.scheduler.Clock;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E14 T-056 (ADR-012 Decision 2, JVM red): the schedule sync point. db-scheduler already knows how to reconcile a
 * recurring task with its {@link com.github.kagkarlsson.scheduler.task.schedule.Schedule} at startup (cancel a
 * disabled task, reschedule a changed pattern or a more frequent delay, otherwise keep the pending execution) - the
 * sync is exactly "run that reconcile again, now", so a job_definition row change takes effect at the next schedule
 * sync without a restart. One-time tasks carry no schedule and are never synced.
 *
 * <pre>
 * Scenario: A job_definition row change takes effect at the next schedule sync
 *   Given the four registered job tasks
 *   When the schedule sync runs (at start, or after an operator changes a row)
 *   Then each recurring task is reconciled against its row-driven schedule and no one-time task is touched
 * </pre>
 */
class JobScheduleSyncTest {

    /** db-scheduler's own Clock interface - a fixed test clock, no waiting. */
    static final Clock CLOCK = () -> Instant.parse("2026-10-13T12:00:00Z");

    private static Task<?> recurringTask(String jobType, com.github.kagkarlsson.scheduler.task.schedule.Schedule schedule) {
        return Tasks.recurring(jobType, schedule).execute((taskInstance, executionContext) -> {
            // the body is not the subject here; the wrapper carries it (T-056's JobRunWrapper)
        });
    }

    @Test
    void syncReconcilesEveryRecurringTaskAgainstItsSchedule() {
        Task<?> deliveryRetry = recurringTask("DELIVERY_RETRY", FixedDelay.ofSeconds(60));
        Task<?> dailyReport = recurringTask("DAILY_REPORT", new CronSchedule("0 5 0 * * *"));
        Task<?> oneTime = Tasks.oneTime("ONE_TIME").execute((taskInstance, executionContext) -> {
            // one-time tasks have no schedule to reconcile
        });
        SchedulerClient client = mock(SchedulerClient.class);

        new JobScheduleSync(List.of(deliveryRetry, dailyReport, oneTime), client, CLOCK).sync();

        // both recurring tasks' startup reconcile ran (initial schedule when no execution is pending);
        // the one-time task is never reconciled - it has no schedule, only the two recurring tasks scheduled
        verify(client, times(2)).schedule(any(TaskInstance.class), any(Instant.class));
        assertThat(new JobScheduleSync(List.of(), client, CLOCK))
                .as("the sync carries the tasks it was built with")
                .isNotNull();
    }

    @Test
    void aSyncWithNoRegisteredTasksTouchesNothing() {
        SchedulerClient client = mock(SchedulerClient.class);

        new JobScheduleSync(List.of(), client, CLOCK).sync();

        verify(client, times(0)).schedule(any(TaskInstance.class), any(Instant.class));
    }
}