package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scanning jobs' tenant list (T-049; ADR-011 Decision 8) through the reviewed definer function
 * {@code ipaas_active_tenants()} (V2_3_1130): the application role sees only its own directory row, so this function
 * is the one place the ids of every ACTIVE tenant are read - ids only, nothing else crosses.
 */
@TenantBoundaryCrossing("ipaas_active_tenants")
final class JdbcActiveTenants implements ActiveTenantDirectory {

    private static final String ACTIVE_TENANTS_SQL = "select tenant_id from public.ipaas_active_tenants()";

    private final JdbcTemplate jdbc;

    JdbcActiveTenants(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<UUID> activeTenantIds() {
        return jdbc.query(ACTIVE_TENANTS_SQL, (rs, row) -> rs.getObject(1, UUID.class));
    }
}
