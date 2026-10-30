package io.integrationplatform.app.scheduler;

import com.github.kagkarlsson.scheduler.Clock;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobScheduleSync;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E14 T-056 (ADR-012 section 1): the closed register as db-scheduler recurring tasks. Each task carries a
 * {@link JobDefinitionSchedule} - the row IS the schedule - and its body is the run/alert wrapper; db-scheduler's
 * own startup reconcile IS the schedule sync at start, and the {@link JobScheduleSync} bean re-runs it on demand
 * so a changed row takes effect at the next sync. No task carries timing of its own (no @Scheduled, no in-memory
 * timer - the arch test refuses).
 */
@Configuration(proxyBeanMethods = false)
public class SchedulerJobTasks {

    public static final String DELIVERY_RETRY = "DELIVERY_RETRY";
    public static final String PAYLOAD_PURGE = "PAYLOAD_PURGE";
    public static final String DAILY_REPORT = "DAILY_REPORT";
    public static final String CREDENTIAL_CLEANUP = "CREDENTIAL_CLEANUP";
    public static final String CALLBACK_SEND = "CALLBACK_SEND";

    /** The recurring task of a register row: row-driven schedule, wrapper body, upstream reschedules. */
    public static RecurringTask<Void> recurringTask(String jobType, JobDefinitionRepository repository,
            JobRunWrapper wrapper) {
        return Tasks.recurring(jobType, new JobDefinitionSchedule(jobType, repository), Void.class)
                .execute((taskInstance, executionContext) -> wrapper.run(jobType));
    }

    @Bean
    Task<Void> deliveryRetryTask(JobDefinitionRepository repository, JobRunWrapper wrapper) {
        return recurringTask(DELIVERY_RETRY, repository, wrapper);
    }

    @Bean
    Task<Void> payloadPurgeTask(JobDefinitionRepository repository, JobRunWrapper wrapper) {
        return recurringTask(PAYLOAD_PURGE, repository, wrapper);
    }

    @Bean
    Task<Void> dailyReportTask(JobDefinitionRepository repository, JobRunWrapper wrapper) {
        return recurringTask(DAILY_REPORT, repository, wrapper);
    }

    @Bean
    Task<Void> credentialCleanupTask(JobDefinitionRepository repository, JobRunWrapper wrapper) {
        return recurringTask(CREDENTIAL_CLEANUP, repository, wrapper);
    }

    @Bean
    Task<Void> callbackSendTask(JobDefinitionRepository repository, JobRunWrapper wrapper) {
        return recurringTask(CALLBACK_SEND, repository, wrapper);
    }

    /** The sync seam: at start db-scheduler runs the same reconcile; T-060's API calls this bean on row change. */
    @Bean
    JobScheduleSync jobScheduleSync(List<Task<?>> tasks, SchedulerClient client, Clock clock) {
        return new JobScheduleSync(tasks, client, clock);
    }
}