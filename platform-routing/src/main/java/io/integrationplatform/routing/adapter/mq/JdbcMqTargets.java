package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row 327: a target endpoint's system type and MQ settings, tenant-scoped. The system is read in a select-list
 * subquery so the bare tenant predicate binds to {@code target_endpoint} only (RLS scopes the subquery's row).
 */
public class JdbcMqTargets extends TenantScopedRepository {

    private static final String SELECT_TYPE = "select (select s.type from public.system s where s.id = te.system_id)"
            + " as v from public.target_endpoint te";
    private static final String SELECT_SETTINGS = "select (select s.settings::text from public.system s"
            + " where s.id = te.system_id and s.type = 'MQ') as v from public.target_endpoint te";

    public JdbcMqTargets(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The endpoint's stored {@code system.type} ("MQ", "API"), empty for no endpoint or no type. */
    public Optional<String> typeOf(UUID targetEndpointId) {
        return one(SELECT_TYPE, targetEndpointId);
    }

    /** The endpoint's MQ system settings JSON, empty unless the endpoint's system is an MQ system. */
    Optional<String> settingsOf(UUID targetEndpointId) {
        return one(SELECT_SETTINGS, targetEndpointId);
    }

    private Optional<String> one(String select, UUID targetEndpointId) {
        return query(select, "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()),
                (rs, rowNum) -> rs.getString("v")).stream().filter(Objects::nonNull).findFirst();
    }
}
