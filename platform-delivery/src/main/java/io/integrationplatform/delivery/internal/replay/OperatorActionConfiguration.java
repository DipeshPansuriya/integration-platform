package io.integrationplatform.delivery.internal.replay;

import io.integrationplatform.delivery.replay.ActiveTargetContracts;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T111 wiring: the operator retry/replay rules over the JDBC store. The tenant context and the outbox writer are
 * resolved per call (the {@code DeliveryPipelineConfiguration} pattern), so a bare delivery slice with neither bean
 * still starts; an unbound call fails closed on {@code requireTenantId()} before any SQL. The transaction manager is
 * the composed app's tenant-stamping one, so the RLS stamp is set for the action's transaction.
 */
@Configuration(proxyBeanMethods = false)
class OperatorActionConfiguration {

    @Bean
    DeliveryOperatorActions deliveryOperatorActions(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts, ObjectProvider<OutboxWriter> outboxWriters,
            ObjectProvider<ActiveTargetContracts> activeContractProviders,
            ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        TenantContext tenant = () -> Optional.ofNullable(tenantContexts.getIfAvailable())
                .flatMap(TenantContext::currentTenantId);
        // an absent adapter resolves nothing: the mappingless delivery stays refused as not pinned (fail closed)
        ActiveTargetContracts activeContracts = target -> activeContractProviders
                .getIfAvailable(() -> unused -> Optional.empty()).activeContractVersionOf(target);
        OutboxWriter outbox = entry -> outboxWriters.getObject().append(entry);
        return new DeliveryOperatorActions(new JdbcOperatorActionStore(jdbcClient, tenant), activeContracts, outbox, events, tenant,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }
}
