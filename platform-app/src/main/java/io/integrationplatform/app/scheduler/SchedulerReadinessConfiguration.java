package io.integrationplatform.app.scheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T198: the {@code schedulerJobs} health contributor; the readiness group (config-secrets.yml) names it. The
 * db-scheduler starter registers no {@code SchedulerState} bean ({@code Scheduler} only exposes it through
 * {@code getSchedulerState()}), so asking for one made the whole context fail (L23).
 */
@Configuration(proxyBeanMethods = false)
class SchedulerReadinessConfiguration {

    @Bean
    SchedulerJobsHealthIndicator schedulerJobs(List<Task<?>> tasks, JobDefinitionRepository repository,
            SchedulerClient client, Scheduler scheduler) {
        return new SchedulerJobsHealthIndicator(tasks, repository, client, scheduler.getSchedulerState());
    }
}
