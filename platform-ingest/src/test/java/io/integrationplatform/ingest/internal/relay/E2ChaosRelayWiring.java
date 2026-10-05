package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.ingest.port.OutboxRelay;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Row 278 wave-0 integration glue (arjun-fullstack, the lead's lane): the relay's Spring registration for the
 * chaos composition (E2-F1-S1, T-009). Lane B (T31) built and proved {@link PostgresOutboxRelay} against the
 * real partitioned outbox with manually constructed beans; lane D's chaos suite looks the relay up by the frozen
 * port from its {@code io.integrationplatform.ingest} scan - this configuration is the composition seam the
 * lanes agreed the integration lead owns. Test scope only: the composed application context never sees it
 * (dependency test sources are not on a dependent module's classpath), so no publish adapter is pretended
 * here - the production {@code PublishPort} arrives with E8's Kafka adapter in platform-routing, and the
 * production active-tenant crossing inside platform-tenancy is a reported E2 open item.
 */
@Configuration(proxyBeanMethods = false)
class E2ChaosRelayWiring {

    /**
     * Lane B's claim and mark (constructible only inside this package - the implementation is package-private).
     * The {@code TenantContext} here resolves to the composition's primary stamp source (the chaos app's
     * composite of the suite's binding and the relay's), so the relay's per-cycle binding is what the
     * transaction stamps and what the tenant predicate reads at every statement (R08, R11).
     */
    @Bean
    OutboxRowAccess jdbcOutboxAccess(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcOutboxAccess(jdbcClient, tenantContext);
    }

    /** The relay as the frozen {@link OutboxRelay} port, production figures: 64 buckets, 200 rows a batch. */
    @Bean
    OutboxRelay outboxRelay(ActiveTenantSource activeTenants, OutboxRowAccess access,
            RelayTenantContext relayTenantContext, TransactionOperations transactions, PublishPort publishPort) {
        return new PostgresOutboxRelay(activeTenants, access, relayTenantContext, transactions, publishPort,
                Duration.ofSeconds(30), 200);
    }
}