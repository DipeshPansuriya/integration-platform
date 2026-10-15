package io.integrationplatform.app.queue;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F10-narrow (row278 release-base plan step 8) wiring. Built on the established pattern of
 * {@code TenancyPersistenceConfiguration} / {@code DeliveryPersistenceConfiguration}: internal wiring, proxy
 * beans off, and the fail-closed rule - with no {@link TenantContext} bean in the context the store still exists
 * and every use throws {@code NoTenantBoundException} before any SQL runs (binding is the caller's concern).
 */
@Configuration(proxyBeanMethods = false)
class QueuePersistenceConfiguration {

    @Bean
    JdbcQueueSummaryStore queueSummaryStore(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcQueueSummaryStore(jdbcClient, bound != null ? bound : neverBound());
    }

    @Bean
    QueueSummaryService queueSummaryService(JdbcQueueSummaryStore store) {
        return new QueueSummaryService(store);
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