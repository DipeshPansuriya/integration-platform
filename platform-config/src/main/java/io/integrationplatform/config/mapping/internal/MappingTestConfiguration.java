package io.integrationplatform.config.mapping.internal;

import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.config.mapping.application.MappingTestService;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingTestRepository;
import io.integrationplatform.config.mapping.internal.persistence.MappingTestRepository;
import io.integrationplatform.config.rule.internal.JdbcLookupSource;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import io.integrationplatform.transform.internal.DashjoinJsonataTransformPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E7 lane B2's mapping-test wiring (T-108/T-109): the mapping-by-id read, the JSONata transform engine behind its
 * 5 s per-transform budget, the tenant-scoped lookup resolver, the sample-run seam's wave-1 no-op bean and the test
 * service - in the lane's own composition file (the MappingPersistenceConfiguration pattern). The budget comes from
 * {@code config/transform.yml} (imported optionally by platform-app's application.yml); its default is exactly
 * T-109's figure, so an unset operator seam answers the 5 s budget rather than a silent figure.
 */
@Configuration(proxyBeanMethods = false)
public class MappingTestConfiguration {

    @Bean
    MappingTestRepository jdbcMappingTestRepository(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcMappingTestRepository(jdbcClient, tenantContexts);
    }

    @Bean
    TransformPort mappingTestTransformPort(@Value("${transform.budget-ms:5000}") long budgetMs) {
        return new DashjoinJsonataTransformPort(budgetMs);
    }

    @Bean
    LookupSource jdbcLookupSource(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcLookupSource(jdbcClient, tenantContexts);
    }

    /** The wave-1 fallback until lane C2's JDBC bean lands: records nothing, never passing (the seam's own words). */
    @Bean
    MappingSampleRuns mappingSampleRuns() {
        return MappingSampleRuns.noop();
    }

    @Bean
    MappingTestService mappingTestService(MappingTestRepository mappings, TransformPort transform,
            LookupSource lookups, MappingSampleRuns sampleRuns) {
        return new MappingTestService(mappings, transform, lookups, sampleRuns);
    }
}