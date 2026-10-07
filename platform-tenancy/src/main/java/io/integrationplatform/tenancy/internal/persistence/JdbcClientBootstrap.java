package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.ClientBinding;
import io.integrationplatform.tenancy.port.ClientBootstrap;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The JDBC side of the trusted pre-tenant path for source systems (ADR-007 Decision 2, ADR-009 "Update 2026-10-02"),
 * the machine twin of {@link JdbcLoginBootstrap}: calls the definer function
 * {@code ipaas_client_bootstrap(issuer, client id)}. It runs with or without a bound tenant; row-level security still
 * applies to every other statement. The function returns rows only for ACTIVE credentials of ACTIVE tenants, and
 * (issuer, client id) is unique, so the result is at most one binding.
 */
@TenantBoundaryCrossing("ipaas_client_bootstrap")
class JdbcClientBootstrap implements ClientBootstrap {

    private static final String BOOTSTRAP_SQL = "select credential_id, credential_status, source_system_id,"
            + " customer_id, tenant_id, tenant_code, tenant_status, time_zone"
            + " from public.ipaas_client_bootstrap(?, ?)";

    private static final RowMapper<ClientBinding> BINDING = (rs, row) -> new ClientBinding(
            rs.getObject("credential_id", UUID.class),
            rs.getString("credential_status"),
            rs.getObject("source_system_id", UUID.class),
            rs.getString("customer_id"),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("tenant_code"),
            rs.getString("tenant_status"),
            ZoneId.of(rs.getString("time_zone")));

    private final JdbcTemplate jdbc;

    JdbcClientBootstrap(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ClientBinding> resolve(String issuer, String clientId) {
        if (issuer == null || issuer.isBlank() || clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(BOOTSTRAP_SQL, BINDING, issuer, clientId).stream().findFirst();
    }
}
