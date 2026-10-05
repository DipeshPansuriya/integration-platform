package io.integrationplatform.ingest.internal.dedup;

import io.integrationplatform.tenancy.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-010 wiring: the application calls the {@link DuplicateGuard}; this is its only implementation. The tenant is
 * never a parameter here - {@link JdbcDuplicateGuard} takes it from the request-bound {@link TenantContext} at
 * claim time and fails closed when none is bound (ADR-007, R08).
 */
@Configuration(proxyBeanMethods = false)
public class DuplicateGuardConfiguration {

    @Bean
    DuplicateGuard duplicateGuard(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcDuplicateGuard(jdbcClient, tenantContext);
    }
}
