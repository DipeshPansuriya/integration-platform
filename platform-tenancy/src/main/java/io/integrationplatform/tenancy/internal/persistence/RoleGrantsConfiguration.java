package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.RoleGrants;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E4 lane B wiring (T-099): the {@link RoleGrants} port. The tenant is read at each call, like the stamp: with no
 * TenantContext bean every call fails closed.
 */
@Configuration(proxyBeanMethods = false)
class RoleGrantsConfiguration {

    @Bean
    RoleGrants roleGrants(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcRoleGrants(jdbcClient,
                () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId));
    }
}
