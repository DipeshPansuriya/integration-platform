package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import io.integrationplatform.config.scheduler.adapter.in.JobAdminController;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Row 327 lane D regression: the job admin API needs db-scheduler's SchedulerClient. A context with db-scheduler
 * off (the platform-config slice apps) must start without it; the platform app (db-scheduler.enabled=true) gets it.
 */
class JobAdminWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JobAdminService.class, JobAdminController.class)
            .withBean(JobDefinitionRepository.class, () -> mock(JobDefinitionRepository.class))
            .withBean(JobRunSearch.class, () -> mock(JobRunSearch.class))
            .withBean(SchedulerRegistry.class, () -> mock(SchedulerRegistry.class))
            .withBean(JobAdminAudit.class, () -> mock(JobAdminAudit.class));

    @Test
    @DisplayName("db-scheduler off and no SchedulerClient: the context starts without the job admin API")
    void startsWithoutScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JobAdminService.class).doesNotHaveBean(JobAdminController.class);
        });
    }

    @Test
    @DisplayName("db-scheduler on: the job admin API is wired")
    void wiredWithScheduler() {
        SchedulerClient client = mock(SchedulerClient.class);
        runner.withPropertyValues("db-scheduler.enabled=true")
                .withBean(SchedulerClient.class, () -> client)
                .withBean(JobScheduleSync.class, () -> mock(JobScheduleSync.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JobAdminService.class).hasSingleBean(JobAdminController.class);
                });
    }
}
