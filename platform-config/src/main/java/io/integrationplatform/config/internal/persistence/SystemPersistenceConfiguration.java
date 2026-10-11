package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.OutboundHttpCheck;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.integrationplatform.config.system.application.TestConnectionAudit;
import io.integrationplatform.config.system.application.TestConnectionService;
import io.integrationplatform.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E5 seam (arjun-fullstack, F13): the systems registry's composition - the repository and the service - moved
 * unchanged out of {@link ConfigPersistenceConfiguration} so E5 lane B (T-102, T-104) owns this file alone. Lane B's
 * beans: the destination guard and its two ports (system DNS, plain TCP connect - a test replaces either with a
 * {@code @Primary} bean), the R13-controlled HTTP check, and test-connection with its per-user rate limit.
 *
 * <p>Settings (config/config-systems.yml, all optional - the defaults fail closed):
 * {@code platform.config.destinations.allowed-hosts} (exact names or {@code *.domain}; empty refuses every
 * destination), {@code platform.config.test-connection.rate-limit.limit} (default 10) and {@code .window} (default 1m).
 */
@Configuration(proxyBeanMethods = false)
class SystemPersistenceConfiguration {

    static final String ALLOWED_HOSTS = "platform.config.destinations.allowed-hosts";
    static final String RATE_LIMIT = "platform.config.test-connection.rate-limit.limit";
    static final String RATE_WINDOW = "platform.config.test-connection.rate-limit.window";

    @Bean
    JdbcSystemRepository jdbcSystemRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcSystemRepository(jdbcClient, tenantContexts);
    }

    @Bean
    HostResolver destinationHostResolver() {
        return HostResolver.system();
    }

    @Bean
    SocketDialer destinationSocketDialer() {
        return SocketDialer.plain();
    }

    @Bean
    DestinationGuard destinationGuard(Environment environment, HostResolver resolver) {
        List<String> allowed = Binder.get(environment).bind(ALLOWED_HOSTS, Bindable.listOf(String.class))
                .orElse(List.of());
        return new DestinationGuard(AllowList.of(allowed), resolver);
    }

    @Bean
    OutboundHttpCheck outboundHttpCheck(DestinationGuard guard, SocketDialer dialer,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations) {
        return new OutboundHttpCheck(guard, dialer, OutboundHttpCheck.Settings.defaults(),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    TestConnectionService testConnectionService(Environment environment, JdbcSystemRepository systems,
            PlatformTransactionManager transactions, OutboundHttpCheck check) {
        Binder binder = Binder.get(environment);
        int limit = binder.bind(RATE_LIMIT, Integer.class).orElse(10);
        Duration window = binder.bind(RATE_WINDOW, Duration.class).orElse(Duration.ofMinutes(1));
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        return new TestConnectionService(systems, readOnly, check, limit, window, new TestConnectionAudit());
    }

    @Bean
    SystemAdminService systemAdminService(JdbcSystemRepository systems, TestConnectionService testConnection) {
        return new SystemAdminService(systems, testConnection);
    }
}
