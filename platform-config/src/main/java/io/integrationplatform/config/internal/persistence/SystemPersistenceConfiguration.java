package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.system.application.SystemAdminService;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E5 seam (arjun-fullstack, F13): the systems registry's composition - the repository and the service - moved
 * unchanged out of {@link ConfigPersistenceConfiguration} so E5 lane B (T-102, T-104) owns this file alone. Lane B's
 * new beans (the destination guard, test-connection, the rate limit) are wired here.
 */
@Configuration(proxyBeanMethods = false)
class SystemPersistenceConfiguration {

    @Bean
    JdbcSystemRepository jdbcSystemRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcSystemRepository(jdbcClient, tenantContexts);
    }

    @Bean
    SystemAdminService systemAdminService(JdbcSystemRepository systems) {
        return new SystemAdminService(systems);
    }
}
