package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row 330 (E10 resolver): a KAFKA target endpoint's stored system settings, tenant-scoped - the Kafka-side analogue
 * of {@link io.integrationplatform.routing.adapter.mq.JdbcMqTargets}. The system's {@code type} is 'KAFKA' and its
 * settings carry bootstrapServers and topic. The bare tenant predicate binds to {@code target_endpoint} only (RLS).
 */
public class JdbcKafkaTargets extends TenantScopedRepository {

    private static final String SELECT_SETTINGS = "select (select s.settings::text from public.system s"
            + " where s.id = te.system_id and s.type = 'KAFKA') as v from public.target_endpoint te";

    public JdbcKafkaTargets(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The endpoint's KAFKA system settings JSON, empty unless the endpoint's system is a KAFKA system. */
    public Optional<String> settingsOf(UUID targetEndpointId) {
        return query(SELECT_SETTINGS, "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()),
                (rs, rowNum) -> rs.getString("v")).stream().filter(Objects::nonNull).findFirst();
    }
}
