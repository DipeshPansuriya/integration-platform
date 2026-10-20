package io.integrationplatform.delivery.internal.trigger;

import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.internal.JdbcDeliveryReconcileStore;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetBreakerFacts;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-180 open item 1 (row 278): the production {@link DeliveryRetryTrigger}, built with the 12-arg constructor -
 * the real {@link JdbcDeliveryReconcileStore} and the application's {@link TargetSendFacts} (the target's
 * {@code timeout_ms} / {@code idempotent_receive}; {@link TargetSendFacts#unknown()} when none is wired). It runs
 * only from the row-based db-scheduler {@code DELIVERY_RETRY} job (ADR-012: no in-JVM timer). Same shape as the
 * outbox relay: Kafka-conditional, and the trigger's {@link TriggerTenantContext} is a bean so the application's
 * composite tenant context sees each tenant it binds.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
class DeliveryRetryTriggerConfiguration {

    static final String DELIVERY_RETRY = "DELIVERY_RETRY";

    // ponytail: one batch per tenant per run; a property arrives when an operator needs to tune it
    private static final int BATCH_SIZE = 200;

    @Bean
    TriggerTenantContext deliveryTriggerTenantContext() {
        return new TriggerTenantContext();
    }

    @Bean
    DeliveryReconcileStore deliveryReconcileStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcDeliveryReconcileStore(jdbcClient, tenantContext);
    }

    @Bean
    DeliveryRetryTrigger deliveryRetryTrigger(ActiveTenantDirectory activeTenants,
            TriggerTenantContext deliveryTriggerTenantContext, PlatformTransactionManager transactionManager,
            DeliveryRetryStore deliveryRetryStore, OutboxWriter outboxWriter,
            DeliveryReconcileStore deliveryReconcileStore, ObjectProvider<TargetSendFacts> targetSendFacts,
            ObjectProvider<InstanceReadiness> instanceReadiness,
            ObjectProvider<DeliveryBulkheadFacts> deliveryBulkheadFacts,
            ObjectProvider<TargetBreakerFacts> targetBreakerFacts) {
        return new DeliveryRetryTrigger(activeTenants::activeTenantIds, deliveryTriggerTenantContext,
                new TransactionTemplate(transactionManager), deliveryRetryStore, outboxWriter, BATCH_SIZE,
                instanceReadiness.getIfAvailable(InstanceReadiness::alwaysReady),
                deliveryBulkheadFacts.getIfAvailable(() -> deliveryId -> Optional.empty()),
                TargetRedriveCap.unlimited(),
                deliveryReconcileStore, targetSendFacts.getIfAvailable(TargetSendFacts::unknown),
                RetryPolicy.defaults(), targetBreakerFacts.getIfAvailable(TargetBreakerFacts::unknown));
    }

    @Bean
    JobHandler deliveryRetryJobHandler(DeliveryRetryTrigger deliveryRetryTrigger) {
        return new JobHandler() {
            @Override
            public String type() {
                return DELIVERY_RETRY;
            }

            @Override
            public void run(String jobData) {
                deliveryRetryTrigger.runOnce();
            }
        };
    }
}
