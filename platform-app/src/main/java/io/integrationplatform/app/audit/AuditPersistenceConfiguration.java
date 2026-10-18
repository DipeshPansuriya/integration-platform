package io.integrationplatform.app.audit;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E12 N3 (T-067 narrow) wiring, on the established pattern of {@code QueuePersistenceConfiguration} /
 * {@code DeliveryPersistenceConfiguration}: internal wiring, proxy beans off, fail-closed - with no
 * {@link TenantContext} bean the store still exists and every append throws
 * {@code NoTenantBoundException} before any SQL runs. The ledger transaction is REQUIRES_NEW on the application's
 * stamping transaction manager: the listener runs after the business commit, so it opens its own tenant-stamped
 * transaction on the same bound tenant (R11 - the stamp rides every transaction the manager starts).
 */
@Configuration(proxyBeanMethods = false)
class AuditPersistenceConfiguration {

    @Bean
    JdbcAuditEventStore auditEventStore(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcAuditEventStore(jdbcClient, bound != null ? bound : neverBound());
    }

    @Bean
    AuditEventWriter auditEventWriter(JdbcAuditEventStore store, ObjectProvider<TenantContext> tenantContexts,
            PlatformTransactionManager transactionManager) {
        // row278 D2/M3: the outbox append rides the business transaction; only the after-commit relay is new
        TransactionTemplate relay = new TransactionTemplate(transactionManager);
        relay.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TenantContext bound = tenantContexts.getIfAvailable();
        return new AuditEventWriter(store, relay, bound != null ? bound : neverBound());
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
