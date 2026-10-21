package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.internal.stage1.JdbcStage1Deliveries;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The delivery ledger as the running pipeline uses it (T-049 composition): the stage-1 fan-out insert (T-117/T-178),
 * the stage-2 claim (T-193) and the send outcomes - the ports routing's stages call, built here because their
 * implementations are this module's internals. The tenant is read at every call from the application's bound
 * context (the request's, or the worker's that consumes a record), never captured: with nothing bound every call
 * fails closed before any SQL runs (R08/R11).
 */
@Configuration(proxyBeanMethods = false)
class DeliveryPipelineConfiguration {

    @Bean
    Stage1Deliveries stage1Deliveries(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            ObjectProvider<StageTimings> timings) {
        return new JdbcStage1Deliveries(new JdbcDeliveryRepository(jdbcClient, boundAtCall(tenantContexts)));
    }

    @Bean
    DeliveryClaimService deliveryClaimService(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new DeliveryClaimService(new JdbcDeliveryRepository(jdbcClient, boundAtCall(tenantContexts)));
    }

    @Bean
    SendOutcomes sendOutcomes(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            ObjectProvider<StageTimings> timings) {
        return new JdbcSendOutcomes(jdbcClient, boundAtCall(tenantContexts));
    }

    private static TenantContext boundAtCall(ObjectProvider<TenantContext> tenantContexts) {
        return () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId);
    }
}
