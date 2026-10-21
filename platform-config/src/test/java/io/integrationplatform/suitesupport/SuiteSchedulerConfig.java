package io.integrationplatform.suitesupport;

import com.github.kagkarlsson.scheduler.Clock;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SystemClock;
import io.integrationplatform.config.scheduler.JobScheduleSync;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scheduler beans a platform-config suite composition needs for {@code JobAdminService} (E14 T-060). In
 * production platform-app supplies them (db-scheduler starter + {@code SchedulerWiringOrderBridge} +
 * {@code SchedulerJobTasks}); platform-config suites do not carry platform-app, so this supplies the same two
 * collaborators: a {@code SchedulerClient} on the suite's DataSource (plain SQL on {@code scheduler.scheduled_tasks},
 * queried only when a job operation runs; building it reads just the vendor name) and a {@code JobScheduleSync} with no tasks.
 *
 * <p>It lives OUTSIDE {@code io.integrationplatform.config} on purpose (see {@code E3LaneCSuiteApp}): every suite
 * composition imports it explicitly with {@code @Import}, nothing scans it.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SuiteSchedulerConfig {

    @Bean
    Clock suiteSchedulerClock() {
        return new SystemClock();
    }

    @Bean
    SchedulerClient suiteSchedulerClient(DataSource dataSource) {
        return SchedulerClient.Builder.create(dataSource).tableName("scheduler.scheduled_tasks").build();
    }

    @Bean
    JobScheduleSync suiteJobScheduleSync(SchedulerClient client, Clock clock) {
        return new JobScheduleSync(List.of(), client, clock);
    }
}
