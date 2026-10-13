package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.mapping.adapter.in.MappingAdminController;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.config.mapping.application.MappingAdminService;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E7 lane C1's mapping wiring (T-190): the mapping repository, the save/read service, the publish pin check and
 * the HTTP seam, in the lane's own composition file (the ConfigPersistenceConfiguration pattern). The pin check's
 * ONE call site sits in {@code FlowAdminService#publish}: the flow service gains it there and nowhere else.
 */
@Configuration(proxyBeanMethods = false)
class MappingPersistenceConfiguration {

    @Bean
    JdbcMappingRepository jdbcMappingRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcMappingRepository(jdbcClient, tenantContexts);
    }

    @Bean
    MappingAdminService mappingAdminService(JdbcMappingRepository mappings) {
        return new MappingAdminService(mappings);
    }

    @Bean
    MappingPublishPinCheck mappingPublishPinCheck(JdbcMappingRepository mappings) {
        return new MappingPublishPinCheck(mappings);
    }
}