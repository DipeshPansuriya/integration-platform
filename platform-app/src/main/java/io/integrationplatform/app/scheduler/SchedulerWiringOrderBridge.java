package io.integrationplatform.app.scheduler;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * E14 T-056 wiring (sanjay-developer, row 278): the db-scheduler spring-boot-starter declares its own order
 * against Spring Boot 3's {@code org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration}, the
 * FQN Spring Boot 4 moved to {@code org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration}.
 * The dead hint voids the sort: the starter's autoconfiguration parses before any DataSource bean definition
 * exists ({@code com.github.kagkarlsson...} sorts before {@code org.springframework...} alphabetically), its
 * {@code @ConditionalOnBean(DataSource)} backs out, and the Scheduler / SchedulerClient beans never register -
 * every bean that injects them (JobOverdueDetector, JobScheduleSync) cancels the whole context (the
 * container-suite cascade on main, 2a4b579: 838 of 840 errors through one missing bean).
 *
 * <p>This bridge re-states both sides of the ordering with the Boot 4 names - after the DataSource
 * autoconfiguration, before the db-scheduler one - and provides nothing else: no beans, ordering only.
 * The rest of the starter is Boot 4 compatible once its condition matches.</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class,
        beforeName = "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration")
public final class SchedulerWiringOrderBridge {
}