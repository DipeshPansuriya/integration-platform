package io.integrationplatform.app.audit;

import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * row278 D2/M3: the running audit outbox relay. It needs the worker binding and the active-tenant crossing, which
 * exist on the worker deployment (the Kafka condition, as {@code OutboxRelayRunnerConfiguration}); without it the
 * after-commit relay of {@link AuditEventWriter} still moves each committed row.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
class AuditRelayConfiguration {

    @Bean
    AuditOutboxRelayLoop auditOutboxRelayLoop(ActiveTenantDirectory activeTenants, WorkerTenantBinding binding,
            PlatformTransactionManager transactionManager, JdbcAuditEventStore auditEventStore) {
        AuditOutboxRelay relay = new AuditOutboxRelay(activeTenants, binding,
                new TransactionTemplate(transactionManager), auditEventStore, 200);
        return new AuditOutboxRelayLoop(relay, Duration.ofSeconds(5));
    }
}
