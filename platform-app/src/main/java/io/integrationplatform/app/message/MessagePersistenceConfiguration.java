package io.integrationplatform.app.message;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E13 N1 (T110) wiring, on the established pattern of {@code QueuePersistenceConfiguration}: internal wiring, proxy
 * beans off, and the fail-closed rule - with no {@link TenantContext} bean in the context the store still exists and
 * every read throws {@code NoTenantBoundException} before any SQL runs (binding is the caller's concern).
 */
@Configuration(proxyBeanMethods = false)
class MessagePersistenceConfiguration {

    @Bean
    JdbcMessageTrackingStore messageTrackingStore(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcMessageTrackingStore(jdbcClient, bound != null ? bound : neverBound());
    }

    @Bean
    MessageTrackingService messageTrackingService(JdbcMessageTrackingStore store) {
        return new MessageTrackingService(store);
    }

    @Bean
    io.integrationplatform.tracking.api.TrackingReadApi trackingReadApi(MessageTrackingService service) {
        return new TrackingReadApiAdapter(service);
    }

    /** The fail-closed no-binding context - the anonymous shape over the two-method contract. */
    private static TenantContext neverBound() {
        return new TenantContext() {
            @Override
            public Optional<UUID> currentTenantId() {
                return Optional.empty();
            }
        };
    }
}
