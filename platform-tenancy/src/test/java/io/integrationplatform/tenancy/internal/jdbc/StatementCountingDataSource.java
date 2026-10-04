package io.integrationplatform.tenancy.internal.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only: wraps the application DataSource so a test can count the statements that reach the database
 * (Connection.createStatement / prepareStatement / prepareCall). Used to prove "refused before any statement".
 */
@TestConfiguration(proxyBeanMethods = false)
class StatementCountingDataSource {

    private static final Set<String> STATEMENT_FACTORIES = Set.of("createStatement", "prepareStatement", "prepareCall");
    private static final AtomicInteger STATEMENTS = new AtomicInteger();

    static void reset() {
        STATEMENTS.set(0);
    }

    static int statements() {
        return STATEMENTS.get();
    }

    @Bean
    static BeanPostProcessor statementCountingDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource ? countingProxy(dataSource) : bean;
            }
        };
    }

    private static DataSource countingProxy(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(StatementCountingDataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    Object result = invoke(target, method, arguments);
                    return result instanceof Connection connection ? countingProxy(connection) : result;
                });
    }

    private static Connection countingProxy(Connection target) {
        return (Connection) Proxy.newProxyInstance(StatementCountingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (STATEMENT_FACTORIES.contains(method.getName())) {
                        STATEMENTS.incrementAndGet();
                    }
                    return invoke(target, method, arguments);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
