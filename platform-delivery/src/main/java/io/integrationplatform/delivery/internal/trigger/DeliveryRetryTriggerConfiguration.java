package io.integrationplatform.delivery.internal.trigger;

import io.integrationplatform.delivery.internal.JdbcDeliveryReconcileStore;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.internal.JdbcDeliveryCircuitFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryCircuitFacts;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetHalfOpenCap;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.delivery.port.UnroutedMessageSweep;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

    /** E10 T96: which deliveries wait on an open circuit - the last delivery event is {@code CIRCUIT_DEFER}. */
    @Bean
    DeliveryCircuitFacts deliveryCircuitFacts(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcDeliveryCircuitFacts(jdbcClient, tenantContext);
    }

    @Bean
    DeliveryRetryTrigger deliveryRetryTrigger(ActiveTenantDirectory activeTenants,
            TriggerTenantContext deliveryTriggerTenantContext, PlatformTransactionManager transactionManager,
            DeliveryRetryStore deliveryRetryStore, OutboxWriter outboxWriter,
            DeliveryReconcileStore deliveryReconcileStore, ObjectProvider<TargetSendFacts> targetSendFacts,
            ObjectProvider<InstanceReadiness> instanceReadiness, DeliveryBulkheadFacts deliveryBulkheadFacts,
            Environment environment, ObjectProvider<UnroutedMessageSweep> unroutedSweep,
            DeliveryCircuitFacts deliveryCircuitFacts, ObjectProvider<TargetHalfOpenCap> halfOpenCap) {
        return new DeliveryRetryTrigger(activeTenants::activeTenantIds, deliveryTriggerTenantContext,
                new TransactionTemplate(transactionManager), deliveryRetryStore, outboxWriter, BATCH_SIZE,
                instanceReadiness.getIfAvailable(InstanceReadiness::alwaysReady), deliveryBulkheadFacts,
                redriveCap(environment),
                deliveryReconcileStore, targetSendFacts.getIfAvailable(TargetSendFacts::unknown),
                RetryPolicy.defaults(), deliveryCircuitFacts, halfOpenCap.getIfAvailable(TargetHalfOpenCap::defaults),
                unroutedSweep.getIfAvailable(UnroutedMessageSweep::none));
    }

    static final String MAX_CONCURRENT_SENDS = "routing.core.stage2.bulkhead.max-concurrent-sends";
    static final String INSTANCE_COUNT = "routing.core.stage2.bulkhead.instance-count";
    private static final int TEAM_DEFAULT_MAX_CONCURRENT_SENDS = 4;

    /**
     * T-199 (ADR-010 section 2): per target, {@code max_concurrent_sends} x the configured instance count. The
     * per-target setting has no store yet (ADR-011 Decision 1), so every target uses the stage-2 bulkhead's
     * configured size ({@value #MAX_CONCURRENT_SENDS}; unset, unresolved, non-numeric or outside 1-32 = the team
     * default 4, as the bulkhead itself reads it). {@value #INSTANCE_COUNT} is the number of running platform
     * instances: default 1, at least 1 (a smaller value fails the start naming the setting).
     */
    static TargetRedriveCap redriveCap(Environment environment) {
        int sends = TEAM_DEFAULT_MAX_CONCURRENT_SENDS;
        try {
            String raw = environment.getProperty(MAX_CONCURRENT_SENDS);
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.strip());
                sends = parsed >= 1 && parsed <= 32 ? parsed : TEAM_DEFAULT_MAX_CONCURRENT_SENDS;
            }
        } catch (IllegalArgumentException unresolvedOrNotANumber) {
            sends = TEAM_DEFAULT_MAX_CONCURRENT_SENDS;
        }
        int instances = environment.getProperty(INSTANCE_COUNT, Integer.class, 1);
        if (instances < 1) {
            throw new IllegalStateException(INSTANCE_COUNT + " must be at least 1: " + instances);
        }
        int perTarget = sends;
        return TargetRedriveCap.of(target -> perTarget, instances);
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
