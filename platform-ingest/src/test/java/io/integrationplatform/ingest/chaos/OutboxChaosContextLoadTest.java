package io.integrationplatform.ingest.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.IntakeTransaction;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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

    /**
     * An inert stand-in for the pool: the only startup query is the tenancy module's login-role guard, which
     * reads "not superuser, not BYPASSRLS, owns no table" - answered here by a result set of false/0/true-next.
     * Anything else answers false/0/null, so a real query fails at once.
     */
    private static DataSource inertDataSource() {
        return proxy(DataSource.class, (m, args) -> m.getName().equals("getConnection")
                ? proxy(Connection.class, (cm, cargs) -> cm.getName().equals("createStatement")
                        ? proxy(Statement.class, (sm, sargs) -> {
                            if (sm.getName().equals("executeQuery")) {
                                return proxy(ResultSet.class, (rm, rargs) -> rm.getName().equals("next") ? Boolean.TRUE : neutral(rm.getReturnType()));
                            }
                            return neutral(sm.getReturnType());
                        })
                        : neutral(cm.getReturnType()))
                : neutral(m.getReturnType()));
    }

    private interface Answer {
        Object answer(Method method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, method, args) -> method.getDeclaringClass() == Object.class
                        ? neutral(method.getReturnType())
                        : answer.answer(method, args)));
    }

    private static Object neutral(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    @Test
    void chaosApplicationContextLoadsAndIncludesTheIntakeWiring() {
        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(OutboxChaosTestApplication.class)
                .withBean("dataSource", DataSource.class, OutboxChaosContextLoadTest::inertDataSource)
                .withPropertyValues("spring.flyway.enabled=false", "spring.main.web-application-type=none",
                        // the job scheduler reads the database's metadata at start; the stand-in has none
                        "spring.autoconfigure.exclude="
                                + "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(IntakeTransaction.class);
                });
    }
}
