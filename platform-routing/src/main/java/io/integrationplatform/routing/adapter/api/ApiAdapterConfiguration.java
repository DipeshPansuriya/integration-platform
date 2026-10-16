package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The API target adapter's wiring (E10 narrow, ADR-015): the adapter is THE {@link TargetCall} bean the layer-1
 * route receives. The guard and the plain TCP dialer are the destination-check beans this module already has
 * (E5, {@code SystemPersistenceConfiguration}) - reused, never rebuilt. The {@link ApiTargetBody} port has no
 * production producer yet: until the transform lane's port and the composition wiring land, the bean below
 * refuses every send (ponytail placeholder - {@code Optional.empty()} means the adapter raises PERMANENT and
 * nothing is dialled; it must be REPLACED, not extended, by the transform integration).
 */
@Configuration(proxyBeanMethods = false)
public class ApiAdapterConfiguration {

    @Bean
    TargetEndpointViews targetEndpointViews(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcTargetEndpointViews(jdbcClient, tenantContext);
    }

    @Bean
    HttpSend apiTargetHttpSend(SocketDialer dialer) {
        return new SocketHttpSend(dialer);
    }

    /**
     * ponytail: a deliberate placeholder - no transform result exists in this slice (E7 owns it), so the body
     * port denies every send and the adapter never dials half a pipeline. The replace-not-extend marker is here
     * so the transform integration cannot accidentally keep the stub alongside the real source.
     */
    @Bean
    ApiTargetBody pendingTransformBodyPort() {
        return send -> java.util.Optional.empty();
    }

    @Bean
    TargetCall apiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort,
            HttpSend send) {
        return new ApiTargetCall(views, guard, bodyPort, send);
    }
}