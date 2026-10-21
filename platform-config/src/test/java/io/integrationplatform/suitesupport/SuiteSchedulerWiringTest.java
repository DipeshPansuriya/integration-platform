package io.integrationplatform.suitesupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import io.integrationplatform.config.scheduler.JobAdminAudit;
import io.integrationplatform.config.scheduler.JobAdminService;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobRunSearch;
import io.integrationplatform.config.scheduler.JobScheduleSync;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * BS-04 / BS-05 regression: {@code JobAdminService} (E14 T-060, platform-config) needs a {@code SchedulerClient} and a
 * {@code JobScheduleSync}. In production both come from platform-app (the db-scheduler starter, ordered by
 * {@code SchedulerWiringOrderBridge}, and {@code SchedulerJobTasks}); a platform-config suite composition that
 * component-scans {@code io.integrationplatform.config} has neither, so its context never loaded. These tests prove
 * the one test-side supply ({@link SuiteSchedulerConfig}) and that every such suite composition imports it. They need
 * no Docker: building a {@code SchedulerClient} reads only the database vendor name from the connection metadata.
 */
class SuiteSchedulerWiringTest {

    @Test
    @DisplayName("the job admin service wires when the suite scheduler config supplies the client and the sync")
    void jobAdminServiceWiresWithTheSuiteSchedulerConfig() {
        new ApplicationContextRunner()
                .withBean(DataSource.class, SuiteSchedulerWiringTest::postgresLikeDataSource)
                .withBean(JobDefinitionRepository.class, () -> mock(JobDefinitionRepository.class))
                .withBean(JobRunSearch.class, () -> mock(JobRunSearch.class))
                .withBean(SchedulerRegistry.class, () -> mock(SchedulerRegistry.class))
                .withBean(JobAdminAudit.class, () -> mock(JobAdminAudit.class))
                .withUserConfiguration(SuiteSchedulerConfig.class, JobAdminService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SchedulerClient.class).hasSingleBean(JobScheduleSync.class)
                            .hasSingleBean(JobAdminService.class);
                });
    }

    @Test
    @DisplayName("every suite composition that scans the config package imports the suite scheduler config")
    void everyConfigScanningSuiteAppImportsTheSuiteSchedulerConfig() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ComponentScan.class));
        List<String> scanningConfig = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("io.integrationplatform")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            ComponentScan scan = type.getAnnotation(ComponentScan.class);
            if (scan == null || !List.of(scan.basePackages()).contains("io.integrationplatform.config")) {
                continue;
            }
            scanningConfig.add(type.getSimpleName());
            Import imported = type.getAnnotation(Import.class);
            if (imported == null || !List.of(imported.value()).contains(SuiteSchedulerConfig.class)) {
                missing.add(type.getName());
            }
        }
        assertThat(scanningConfig).as("the suite compositions found").hasSizeGreaterThanOrEqualTo(6);
        assertThat(missing).as("suite compositions that scan the config package without the scheduler supply")
                .isEmpty();
    }

    /** A DataSource whose connection answers the one metadata question db-scheduler's client builder asks. */
    private static DataSource postgresLikeDataSource() {
        try {
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
            Connection connection = mock(Connection.class);
            when(connection.getMetaData()).thenReturn(metaData);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
