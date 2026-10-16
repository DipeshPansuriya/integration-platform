package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.ingest.port.OutboxRelay;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The running relay (T-049 composition; ADR-005 Decisions 5 and 12, ADR-011 Decision 8): when the deployment runs a
 * Kafka backbone - the same condition that wires the Kafka {@link PublishPort} (T-110) - the outbox relay cycles for
 * the life of the application. It loops over the tenants of the reviewed {@code ipaas_active_tenants()} crossing,
 * binds one tenant per transaction through its own {@link RelayTenantContext} (a bean, so the application's stamp
 * source sees the binding), and runs with the T-191 controls: the Kafka breaker, the back-off, the age alert, the
 * metrics. 30 s acknowledge timeout and 200 rows a batch are the production figures the E2 chaos suite proved.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
class OutboxRelayRunnerConfiguration {

    @Bean
    RelayTenantContext relayTenantContext() {
        return new RelayTenantContext();
    }

    @Bean
    OutboxRelay outboxRelay(ActiveTenantDirectory activeTenants, JdbcClient jdbcClient,
            RelayTenantContext relayTenantContext, PlatformTransactionManager transactionManager,
            PublishPort publishPort, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        return new PostgresOutboxRelay(activeTenants::activeTenantIds,
                new JdbcOutboxAccess(jdbcClient, relayTenantContext), relayTenantContext,
                new TransactionTemplate(transactionManager), publishPort, Duration.ofSeconds(30), 200,
                RelaySettings.defaults(), meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), Clock.systemUTC(), RelayAlerts.LOG);
    }

    @Bean
    OutboxRelayLoop outboxRelayLoop(OutboxRelay outboxRelay) {
        return new OutboxRelayLoop(outboxRelay, Duration.ofMillis(200));
    }
}
