package io.integrationplatform.delivery.internal.deferral;

import io.integrationplatform.delivery.internal.ordering.JdbcOrderedStateChange;
import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.BulkheadRedrive;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The stage-2 bulkhead's delivery ports (T-177): built here because their implementations are this module's
 * internals, exposed only as port types. The tenant is read from the bound context at every call, never captured.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryBulkheadBeansConfiguration {

    @Bean
    DeliveryBulkheadFacts deliveryBulkheadFacts(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            PlatformTransactionManager transactionManager) {
        return new JdbcDeliveryBulkheadFacts(jdbcClient, boundAtCall(tenantContexts),
                new TransactionTemplate(transactionManager));
    }

    @Bean
    BulkheadDeferrals bulkheadDeferrals(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            PlatformTransactionManager transactionManager) {
        TenantContext tenant = boundAtCall(tenantContexts);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return new JdbcBulkheadDeferrals(jdbcClient, tenant, transactions,
                new JdbcOrderedStateChange(jdbcClient, tenant, transactions));
    }

    @Bean
    BulkheadRedrive bulkheadRedrive(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            PlatformTransactionManager transactionManager, OutboxWriter outboxWriter) {
        return new JdbcBulkheadRedrive(jdbcClient, boundAtCall(tenantContexts),
                new TransactionTemplate(transactionManager), outboxWriter);
    }

    private static TenantContext boundAtCall(ObjectProvider<TenantContext> tenantContexts) {
        return () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId);
    }
}
