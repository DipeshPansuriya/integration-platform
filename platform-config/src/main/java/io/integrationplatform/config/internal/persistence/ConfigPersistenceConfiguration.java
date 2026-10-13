package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.flow.application.FlowAdminService;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The composition of the configuration module's API objects (the DeliveryPersistenceConfiguration pattern, one
 * cut larger): repositories, the services they serve and the actor resolver, as plain classes wired here - no
 * stereotype scanning of application code.
 *
 * <p>The repositories take {@link TenantContext} through an {@code ObjectProvider} with a fail-closed fallback:
 * a composition without the identity chain (lane A's {@code ConfigE3App}) still boots, and its calls refuse
 * because no tenant is bound - the same documented behaviour as the stamping transaction manager's.
 *
 * <p>E5 seam (arjun-fullstack, F13): the systems wiring moved to {@link SystemPersistenceConfiguration} (E5 lane B)
 * and the contracts wiring to {@link ContractPersistenceConfiguration} (E5 lane A), so no two lanes edit one
 * composition file. This class keeps the flows and the actor resolver; a new E5 bean goes into its lane's own file.
 */
@Configuration(proxyBeanMethods = false)
class ConfigPersistenceConfiguration {

    @Bean
    JdbcFlowRepository jdbcFlowRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcFlowRepository(jdbcClient, tenantContexts);
    }

    @Bean
    FlowAdminService flowAdminService(JdbcFlowRepository flows, SystemAdminService systems,
            ContractAdminService contracts, MappingPublishPinCheck mappingPins) {
        return new FlowAdminService(flows, systems, contracts, mappingPins);
    }

    @Bean
    ConfigActors configActors(LoginBootstrap loginBootstrap) {
        return new ConfigActors(loginBootstrap);
    }
}
