package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.WorkTenantLookup;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The stage consumers' work-tenant crossing (T-049; ADR-011 Decision 8, mahesh-architect's ruling m-6f7aa7): one call
 * of the protected definer function {@code ipaas_work_tenant(kind, id)} (V2_3_811), which reads the named row as its
 * owner and returns only its tenant id. The kind is one of the function's two closed values - anything else is refused
 * before any SQL runs.
 */
@TenantBoundaryCrossing("ipaas_work_tenant")
final class JdbcWorkTenants implements WorkTenantLookup {

    private static final String WORK_TENANT_SQL = "select public.ipaas_work_tenant(?, ?)";

    private static final Set<String> KINDS = Set.of("MESSAGE", "DELIVERY");

    private final JdbcTemplate jdbc;

    JdbcWorkTenants(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public UUID tenantOf(String kind, UUID id) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("not a work kind of ipaas_work_tenant: " + kind);
        }
        Objects.requireNonNull(id, "id");
        return jdbc.query(WORK_TENANT_SQL, (rs, row) -> rs.getObject(1, UUID.class), kind, id).stream()
                .filter(Objects::nonNull).findFirst().orElse(null);
    }
}
