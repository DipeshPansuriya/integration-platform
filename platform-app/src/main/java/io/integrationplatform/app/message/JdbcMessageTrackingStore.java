package io.integrationplatform.app.message;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E13 N1 (T110 RED skeleton): the read-only message tracking reads - not built yet.
 */
final class JdbcMessageTrackingStore extends TenantScopedRepository {

    JdbcMessageTrackingStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }
}
