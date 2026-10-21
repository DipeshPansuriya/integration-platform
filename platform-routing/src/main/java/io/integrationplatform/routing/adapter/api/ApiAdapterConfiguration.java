package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.delivery.port.TargetHalfOpenCap;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The API target adapter's wiring (E10 narrow, ADR-015): the adapter is THE {@link TargetCall} bean the layer-1
 * route receives. The guard and the plain TCP dialer are the destination-check beans this module already has
 * (E5, {@code SystemPersistenceConfiguration}) - reused, never rebuilt. The {@link ApiTargetBody} port's one
 * producer is the composition root's transform link (E11 T-049, platform-app {@code TransformedApiBody}): the
 * delivery's pinned mapping version through the transform port; no body means the adapter raises PERMANENT and
 * nothing is dialled.
 */
@Configuration(proxyBeanMethods = false)
public class ApiAdapterConfiguration {

    @Bean
    TargetEndpointViews targetEndpointViews(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcTargetEndpointViews(jdbcClient, tenantContext);
    }

    /** E10 T96: the delivery-retry trigger's per-target half-open cap, from the target's system settings. */
    @Bean
    TargetHalfOpenCap targetHalfOpenCap(JdbcClient jdbcClient,
            TenantContext tenantContext) {
        return new JdbcTargetHalfOpenCap(jdbcClient, tenantContext);
    }

    @Bean
    HttpSend apiTargetHttpSend(SocketDialer dialer) {
        return new SocketHttpSend(dialer);
    }

    @Bean
    TargetCall apiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort,
            HttpSend send, ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<PlatformTransactionManager> transactionManager) {
        PlatformTransactionManager transactions = transactionManager.getIfAvailable();
        if (transactions != null) {
            // E9-C9 (T71): the endpoint and body reads each run in their OWN short tenant-stamped transaction. The
            // network call between them holds no pooled connection - a hanging target must never pin the shared
            // database pool, or every stage and every other target starves behind it.
            TransactionTemplate tx = new TransactionTemplate(transactions);
            TargetEndpointViews endpointViews = views;
            ApiTargetBody body = bodyPort;
            views = endpointId -> tx.execute(status -> endpointViews.byEndpointId(endpointId));
            bodyPort = delivery -> tx.execute(status -> body.bodyFor(delivery));
        }
        return new ApiTargetCall(views, guard, bodyPort, send, ApiTargetCall.defaultBreakers(),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}