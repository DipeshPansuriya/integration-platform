package io.integrationplatform.app.composition;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.mq.MqTargetBody;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The composition root's own part of the running pipeline (E11 T-049, row 278 T106): the transform -> API adapter
 * link (the {@link ApiTargetBody} the adapter configuration's placeholder named "replaced by the transform
 * integration") and, when background work runs, the application's one {@link TenantContext}.
 */
@Configuration(proxyBeanMethods = false)
class CompositionConfiguration {

    @Bean
    ApiTargetBody transformedApiBody(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            TransformPort transformPort, LookupSource lookupSource) {
        TenantContext boundAtCall =
                () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId);
        return new TransformedApiBody(jdbcClient, boundAtCall, transformPort, lookupSource);
    }

    /** Row 327: the MQ message is the transformed API body's bytes - one transform lookup, inside the same tenant transaction. */
    @Bean
    MqTargetBody transformedMqBody(ApiTargetBody transformedApiBody) {
        return send -> transformedApiBody.bodyFor(send).map(ApiTargetBody.ApiWireBody::bytes);
    }

    /**
     * With a Kafka backbone the relay and the stage consumers bind tenants on their own threads; this composite is
     * then the TenantContext every injection point and the stamping transaction manager resolve.
     */
    @Bean
    @Primary
    @ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
    TenantContext workerAwareTenantContext(ObjectProvider<TenantContext> contexts) {
        return new WorkerAwareTenantContext(contexts);
    }
}
