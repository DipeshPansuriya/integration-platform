package io.integrationplatform.routing.adapter.file;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T-150: a FILE target endpoint's stored system settings, tenant-scoped (the JdbcKafkaTargets shape). */
public class JdbcFileTargets extends TenantScopedRepository {

    private static final String SELECT_SETTINGS = "select (select s.settings::text from public.system s"
            + " where s.id = te.system_id and s.type = 'FILE') as v from public.target_endpoint te";

    public JdbcFileTargets(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The endpoint's FILE system settings JSON, empty unless the endpoint's system is a FILE system. */
    public Optional<String> settingsOf(UUID targetEndpointId) {
        return query(SELECT_SETTINGS, "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()),
                (rs, rowNum) -> rs.getString("v")).stream().filter(Objects::nonNull).findFirst();
    }
}
