package io.integrationplatform.tenancy;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Tenant-scoped repository base (T-004). RED SKELETON - not implemented yet. */
public abstract class TenantScopedRepository {

    public static final String TENANT_PARAMETER = "tenant_id";

    private final JdbcClient jdbcClient;
    private final TenantContext tenantContext;

    protected TenantScopedRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        this.jdbcClient = jdbcClient;
        this.tenantContext = tenantContext;
    }

    protected final <T> List<T> query(String selectFrom, String condition, Map<String, ?> parameters,
            RowMapper<T> rowMapper) {
        throw new UnsupportedOperationException("T-004 not implemented");
    }

    protected final int update(String updateOrDelete, String condition, Map<String, ?> parameters) {
        throw new UnsupportedOperationException("T-004 not implemented");
    }
}
