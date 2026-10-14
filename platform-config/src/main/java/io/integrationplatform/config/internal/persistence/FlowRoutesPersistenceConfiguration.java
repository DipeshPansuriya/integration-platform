package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.flow.application.FlowRoutesQuery;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T81 / B9 wiring: the routes-listing read model in its own composition file (the E5 seam rule - a new bean goes into
 * its lane's own file, so no two lanes edit {@code ConfigPersistenceConfiguration}). It reuses that file's
 * {@link JdbcFlowRepository} bean.
 */
@Configuration(proxyBeanMethods = false)
class FlowRoutesPersistenceConfiguration {

    @Bean
    JdbcFlowRouteNames jdbcFlowRouteNames(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcFlowRouteNames(jdbcClient, tenantContexts);
    }

    @Bean
    FlowRoutesQuery flowRoutesQuery(JdbcFlowRepository flows, JdbcFlowRouteNames names) {
        return new FlowRoutesQuery(flows, names);
    }
}
