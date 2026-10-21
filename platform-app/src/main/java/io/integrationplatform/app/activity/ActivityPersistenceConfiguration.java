package io.integrationplatform.app.activity;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row327 T118 (E12-F2-S2) wiring, on the established pattern of {@code MessagePersistenceConfiguration}: internal
 * wiring, proxy beans off, and the fail-closed rule - with no {@link TenantContext} bean in the context the store still
 * exists and every read throws {@code NoTenantBoundException} before any SQL runs.
 */
@Configuration(proxyBeanMethods = false)
class ActivityPersistenceConfiguration {

    @Bean
    JdbcActivityStore activityStore(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcActivityStore(jdbcClient, bound != null ? bound : neverBound());
    }

    @Bean
    ActivityService activityService(JdbcActivityStore store) {
        return new ActivityService(store);
    }

    @Bean
    io.integrationplatform.audit.api.ActivityReadApi activityReadApi(ActivityService service) {
        return new ActivityReadApiAdapter(service);
    }

    @Bean
    io.integrationplatform.audit.api.ActivityWriteApi activityWriteApi(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts) {
        TenantContext bound = tenantContexts.getIfAvailable();
        return new JdbcActivityWriter(jdbcClient, bound != null ? bound : neverBound());
    }

    /** The fail-closed no-binding context - the anonymous shape over the two-method contract. */
    private static TenantContext neverBound() {
        return new TenantContext() {
            @Override
            public Optional<UUID> currentTenantId() {
                return Optional.empty();
            }
        };
    }
}
