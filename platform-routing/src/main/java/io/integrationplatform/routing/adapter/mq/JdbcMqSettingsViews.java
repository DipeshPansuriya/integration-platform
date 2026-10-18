package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads an MQ system's settings from the registry ({@code public.system}), tenant-scoped, every fragment a
 * constant (R08). shortcut: no Postgres container test of its own yet, add one with the broker leg (T98).
 */
public class JdbcMqSettingsViews extends TenantScopedRepository implements MqSettingsViews {

    private static final String SELECT_SETTINGS = "select s.settings::text as settings from public.system s";

    public JdbcMqSettingsViews(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public Optional<MqSettings> bySystemId(UUID systemId) {
        return query(SELECT_SETTINGS, "s.id = :id::uuid and s.type = 'MQ'", Map.of("id", systemId.toString()),
                (rs, rowNum) -> rs.getString("settings")).stream().findFirst().map(MqSettings::fromJson);
    }
}
