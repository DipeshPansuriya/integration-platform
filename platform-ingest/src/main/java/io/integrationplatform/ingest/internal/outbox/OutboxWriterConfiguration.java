package io.integrationplatform.ingest.internal.outbox;

import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-007 wiring: the application calls the frozen port {@link OutboxWriter}; this is its only implementation
 * (intake transactions and lane B's re-drive inject it; a crash path never bypasses it). The tenant is never a
 * parameter here - {@link JdbcOutboxWriter} takes it from the request-bound {@link TenantContext} at append time
 * and fails closed when none is bound (ADR-007, R08).
 */
@Configuration(proxyBeanMethods = false)
public class OutboxWriterConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcOutboxWriter(jdbcClient, tenantContext);
    }
}
