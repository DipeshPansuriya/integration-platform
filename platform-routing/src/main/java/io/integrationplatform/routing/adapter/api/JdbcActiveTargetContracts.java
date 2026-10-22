package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.delivery.replay.ActiveTargetContracts;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T172: the active contract of a target endpoint - the newest APPROVED version of a {@code TARGET_REQUEST} contract
 * of the endpoint's system. The operator replay of a delivery whose route has no mapping pins it. Tenant-scoped
 * (base predicate plus the tenant on every sub-select); another tenant's endpoint resolves to nothing.
 */
public final class JdbcActiveTargetContracts extends TenantScopedRepository implements ActiveTargetContracts {

    public JdbcActiveTargetContracts(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public Optional<UUID> activeContractVersionOf(UUID targetEndpointId) {
        return query(
                "select (select cv.id from public.contract_version cv"
                        + " join public.contract c on c.tenant_id = cv.tenant_id and c.id = cv.contract_id"
                        + " where c.tenant_id = te.tenant_id and c.system_id = te.system_id"
                        + " and c.role = 'TARGET_REQUEST' and cv.status = 'APPROVED'"
                        + " order by cv.created_at desc, cv.id desc limit 1) from public.target_endpoint te",
                "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()), (rs, n) -> rs.getString(1))
                .stream().filter(id -> id != null).findFirst().map(UUID::fromString);
    }
}
