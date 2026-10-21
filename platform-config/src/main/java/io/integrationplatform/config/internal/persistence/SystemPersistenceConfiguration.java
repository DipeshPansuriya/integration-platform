package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.FileRoots;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.MqConnectionCheck;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *
 * <p>T138, SHOWCASE ONLY: {@code platform.config.destinations.showcase-local-hosts} - exact allow-listed host names
 * that may resolve to a loopback or private address so a showcase reaches a local test destination. Honoured only
 * while the {@code showcase} profile is active (a WARN is logged at startup); set without that profile the
 * application refuses to start. Absent or empty, the guard is unchanged. B21 (T137 F1/F2): it needs
 * {@code platform.config.destinations.showcase-local-ports} - the exact ports of the local test destination (a range
 * is lifted only on host AND port); hosts without ports, or the exception next to any profile outside
 * {@link #SHOWCASE_COMPANIONS} (M1, CWE-184: an allow-list, so {@code preprod}, {@code live} or {@code prod-eu} refuse
 * as {@code prod} does), refuse to start.
 */
@Configuration(proxyBeanMethods = false)
class SystemPersistenceConfiguration {

    static final String ALLOWED_HOSTS = "platform.config.destinations.allowed-hosts";
    static final String RATE_LIMIT = "platform.config.test-connection.rate-limit.limit";
    static final String RATE_WINDOW = "platform.config.test-connection.rate-limit.window";
    static final String SHOWCASE_LOCAL_HOSTS = "platform.config.destinations.showcase-local-hosts";
    static final String SHOWCASE_LOCAL_PORTS = "platform.config.destinations.showcase-local-ports";
    /** T-184: the platform's own hosts (exact names or IP literals) - never a destination, whatever is allowed. */
    static final String PLATFORM_HOSTS = "platform.config.destinations.platform-hosts";
    static final String SHOWCASE_PROFILE = "showcase";
    /** The only profiles the local-host exception may run next to; any other active profile refuses to start. */
    static final Set<String> SHOWCASE_COMPANIONS = Set.of(SHOWCASE_PROFILE, "local", "dev", "test");

    private static final Logger LOG = LoggerFactory.getLogger(SystemPersistenceConfiguration.class);

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
        Binder binder = Binder.get(environment);
        List<String> allowed = binder.bind(ALLOWED_HOSTS, Bindable.listOf(String.class)).orElse(List.of());
        List<String> showcaseLocal = binder.bind(SHOWCASE_LOCAL_HOSTS, Bindable.listOf(String.class))
                .orElse(List.of());
        List<Integer> showcasePorts = binder.bind(SHOWCASE_LOCAL_PORTS, Bindable.listOf(Integer.class))
                .orElse(List.of());
        List<String> platformHosts = binder.bind(PLATFORM_HOSTS, Bindable.listOf(String.class)).orElse(List.of());
        if (showcaseLocal.isEmpty()) {
            return new DestinationGuard(AllowList.of(allowed), resolver, List.of(), List.of(), platformHosts);
        }
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (!active.contains(SHOWCASE_PROFILE)) {
            throw new IllegalStateException(SHOWCASE_LOCAL_HOSTS + " is set but the '" + SHOWCASE_PROFILE
                    + "' profile is not active - refusing to start: the local-host exception is showcase-only");
        }
        if (!SHOWCASE_COMPANIONS.containsAll(active)) {
            throw new IllegalStateException(SHOWCASE_LOCAL_HOSTS + " is set next to a profile outside "
                    + SHOWCASE_COMPANIONS + " (active: " + active + ")"
                    + " - refusing to start: the local-host exception is showcase-only");
        }
        if (showcasePorts.isEmpty()) {
            throw new IllegalStateException(SHOWCASE_LOCAL_PORTS + " must list the exact port(s) of the local test"
                    + " destination while " + SHOWCASE_LOCAL_HOSTS + " is set - refusing to start");
        }
        DestinationGuard guard = new DestinationGuard(AllowList.of(allowed), resolver, showcaseLocal, showcasePorts,
                platformHosts);
        LOG.warn("SHOWCASE ONLY: {}={} on {}={} - these allow-listed hosts may resolve to loopback or private"
                + " addresses on these ports only (profile '{}'); remove the properties to restore the refusal",
                SHOWCASE_LOCAL_HOSTS, showcaseLocal, SHOWCASE_LOCAL_PORTS, showcasePorts, SHOWCASE_PROFILE);
        return guard;
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
            PlatformTransactionManager transactions, OutboundHttpCheck check,
            @Qualifier("mqConnectionCheck") ObjectProvider<MqConnectionCheck> mq,
            @Qualifier("kafkaTopicConnectionCheck") ObjectProvider<MqConnectionCheck> kafka,
            @Qualifier("dbConnectionCheck") ObjectProvider<MqConnectionCheck> db,
            @Qualifier("fileConnectionCheck") ObjectProvider<MqConnectionCheck> file) {
        Binder binder = Binder.get(environment);
        int limit = binder.bind(RATE_LIMIT, Integer.class).orElse(10);
        Duration window = binder.bind(RATE_WINDOW, Duration.class).orElse(Duration.ofMinutes(1));
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        // type-keyed checks map (E16-T0): "MQ" always when its adapter is on the classpath, "KAFKA" only behind
        // IPASS_KAFKA_TOPIC_TARGET_ENABLED - with the gate off the behavior is unchanged from T-126
        Map<String, MqConnectionCheck> checks = new LinkedHashMap<>();
        MqConnectionCheck mqCheck = mq.getIfAvailable();
        if (mqCheck != null) {
            checks.put("MQ", mqCheck);
        }
        MqConnectionCheck kafkaCheck = kafka.getIfAvailable();
        if (kafkaCheck != null) {
            checks.put("KAFKA", kafkaCheck);
        }
        // E16 (T-149, T-150): "DB" and "FILE" the same way, each only behind its own adapter gate
        MqConnectionCheck dbCheck = db.getIfAvailable();
        if (dbCheck != null) {
            checks.put("DB", dbCheck);
        }
        MqConnectionCheck fileCheck = file.getIfAvailable();
        if (fileCheck != null) {
            checks.put("FILE", fileCheck);
        }
        return new TestConnectionService(systems, readOnly, check, limit, window, new TestConnectionAudit(),
                Map.copyOf(checks));
    }

    /**
     * F4 (CWE-73/22): the operator's FILE base folders, {@code platform.config.destinations.file-base-dirs}; absent or
     * empty, every FILE target is refused at save and at send (fail closed).
     */
    @Bean
    FileRoots fileRoots(Environment environment) {
        return FileRoots.of(Binder.get(environment).bind(FileRoots.PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of()));
    }

    @Bean
    SystemAdminService systemAdminService(JdbcSystemRepository systems, TestConnectionService testConnection,
            DestinationGuard guard, FileRoots fileRoots, ObjectProvider<TenantContext> tenantContexts) {
        return new SystemAdminService(systems, testConnection, guard, fileRoots,
                () -> tenantContexts.getIfAvailable(() -> () -> Optional.empty()).currentTenantId());
    }
}
