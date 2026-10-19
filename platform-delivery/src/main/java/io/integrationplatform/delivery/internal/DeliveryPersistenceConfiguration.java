package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row 278 wave-0 integration glue (arjun-fullstack, the lead's lane): the bean registration lane B's composed
 * delivery context expects. Lane C (T32) built and proved {@link JdbcDeliveryRetryStore} on the real partitioned
 * tables with its own fixture; lane B's container proof (T-090, {@code DeliveryRetryTriggerPostgresTest})
 * requires exactly this bean to exist in a scanned delivery context - the composition seam the lanes agreed the
 * integration lead owns. {@code TenancyPersistenceConfiguration} (T-157) is the established pattern this
 * follows: internal wiring, proxy beans off, the frozen port as the exposed type.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryPersistenceConfiguration {

    /**
     * The store as the frozen {@link DeliveryRetryStore} contract. With no {@link TenantContext} bean in the
     * scanned context (a bare delivery slice, or the composed app before its request wiring), the bean still
     * exists and every use fails closed: an unbound context answers {@link Optional#empty()}, so
     * {@code requireTenantId()} throws {@code NoTenantBoundException} before any SQL runs (R08/R11) - binding
     * is the caller's (the transaction's) concern, never the store's.
     */
    @Bean
    DeliveryRetryStore deliveryRetryStore(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcDeliveryRetryStore(jdbcClient, bound != null ? bound : neverBound());
    }

    /** T-138: the daily delivery pre-aggregate, the same fail-closed binding shape as the retry store. */
    @Bean
    DeliveryDailyAggregates deliveryDailyAggregates(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcDeliveryDailyAggregateStore(jdbcClient, bound != null ? bound : neverBound());
    }

    /** The fail-closed no-binding context - the anonymous shape over the two-method contract. */
    private static TenantContext neverBound() {
        return new TenantContext() {
            @Override
            public java.util.Optional<java.util.UUID> currentTenantId() {
                return Optional.empty();
            }
        };
    }
}