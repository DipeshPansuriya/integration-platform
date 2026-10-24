package io.integrationplatform.tenancy.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * K1 wiring (ledger row 393, Karan m-aec16c): the guard was {@code @ConditionalOnBean(DataSource.class)} on a scanned
 * component. Component scanning is evaluated before {@link DataSourceAutoConfiguration} registers the DataSource, so
 * the condition was false and the guard was never created. This reproduces the real ordering - the guard is scanned
 * first, the DataSource comes from the auto-configuration - with no Docker: a post-processor swaps the pooled
 * DataSource for a stub that answers "clean role", so only the wiring is under test.
 */
class DatabaseRoleGuardWiringTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = DatabaseRoleGuard.class, useDefaultFilters = false,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DatabaseRoleGuard.class))
    static class ScansTheGuard {

        @Bean
        static BeanPostProcessor cleanRoleDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ? cleanRole() : bean;
                }
            };
        }
    }

    private static DataSource cleanRole() {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet result = mock(ResultSet.class); // next() true, booleans false, counts 0: a clean role
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(result);
            when(result.next()).thenReturn(true);
            return dataSource;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ScansTheGuard.class)
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost/none");

    @Test
    @DisplayName("a scanned guard exists exactly once when the DataSource comes from auto-configuration")
    void theGuardIsCreatedOnceInTheRealOrdering() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DatabaseRoleGuard.class);
        });
    }

    @Test
    @DisplayName("two DataSources and none primary: the guard fails closed instead of silently checking nothing")
    void failsClosedWhenTheDataSourceIsNotUnique() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScansTheGuard.class)
                .withBean("first", DataSource.class, DatabaseRoleGuardWiringTest::cleanRole)
                .withBean("second", DataSource.class, DatabaseRoleGuardWiringTest::cleanRole)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "K1: more than one DataSource and none is primary - cannot tell which login the application uses");
                });
    }

    @Test
    @DisplayName("the guard opts out of lazy initialisation so spring.main.lazy-initialization=true cannot skip it")
    void theGuardIsNeverLazy() {
        org.springframework.context.annotation.Lazy lazy =
                DatabaseRoleGuard.class.getAnnotation(org.springframework.context.annotation.Lazy.class);
        assertThat(lazy).isNotNull();
        assertThat(lazy.value()).isFalse();
    }
}
