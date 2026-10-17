package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.contract.api.ContractVersions;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.contract.application.ContractVersionResolver;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E5 seam (arjun-fullstack, F13): the contracts' composition - the repository and the service - moved unchanged out
 * of {@link ConfigPersistenceConfiguration} so E5 lane A (T-105, T-106) owns this file alone. Lane A's new beans
 * (schema import, non-author approval, the per-message contract version) are wired here.
 */
@Configuration(proxyBeanMethods = false)
class ContractPersistenceConfiguration {

    @Bean
    JdbcContractRepository jdbcContractRepository(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcContractRepository(jdbcClient, tenantContexts);
    }

    @Bean
    ContractAdminService contractAdminService(JdbcContractRepository contracts, JdbcSystemRepository systems,
            ApplicationEventPublisher events, ObjectProvider<TenantContext> tenantContexts) {
        // E12-F2-S1 (T-134): the import and approval audit events name the bound tenant; none bound fails closed
        return new ContractAdminService(contracts, systems, events,
                tenantContexts.getIfAvailable(() -> java.util.Optional::empty));
    }

    /** T-106: the public port other modules ask "which contract version does a message use now". */
    @Bean
    ContractVersions contractVersions(ContractAdminService contracts) {
        return new ContractVersionResolver(contracts);
    }
}
