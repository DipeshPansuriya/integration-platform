package io.integrationplatform.ingest.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.IntakeTransaction;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * B43: the chaos wiring scans {@code io.integrationplatform.ingest}, so every bean the module's production
 * configuration declares must resolve inside it - including the intake's {@code PayloadValidationService} (B37),
 * which needs the configuration module's {@code RuleEvaluationPort}. This is the Docker-free guard: it loads the
 * same context with only the Postgres-backed {@code DataSource} swapped for an inert stand-in, so a missing bean
 * fails here in the unit run instead of 3 of 3 errors in the container gate.
 */
class OutboxChaosContextLoadTest {

    private static DataSource inertDataSource() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("no database in the context-load guard: " + method);
                });
    }

    @Test
    void chaosApplicationContextLoadsAndIncludesTheIntakeWiring() {
        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(OutboxChaosTestApplication.class)
                .withBean("dataSource", DataSource.class, OutboxChaosContextLoadTest::inertDataSource)
                .withPropertyValues("spring.flyway.enabled=false", "spring.main.web-application-type=none")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(IntakeTransaction.class);
                });
    }
}
