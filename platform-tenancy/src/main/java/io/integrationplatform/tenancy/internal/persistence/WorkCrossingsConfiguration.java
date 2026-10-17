package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import io.integrationplatform.tenancy.port.WorkTenantLookup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The background work's two reviewed crossings (T-049 composition; ADR-011 Decision 8): the active-tenant list the
 * scanning jobs loop over and the work-tenant lookup the stage consumers resolve a record's row through. Both are
 * ids-only definer-function calls; everything the work does afterwards runs tenant-bound under row-level security.
 */
@Configuration(proxyBeanMethods = false)
class WorkCrossingsConfiguration {

    @Bean
    ActiveTenantDirectory activeTenantDirectory(JdbcTemplate jdbcTemplate) {
        return new JdbcActiveTenants(jdbcTemplate);
    }

    @Bean
    WorkTenantLookup workTenantLookup(JdbcTemplate jdbcTemplate) {
        return new JdbcWorkTenants(jdbcTemplate);
    }
}
