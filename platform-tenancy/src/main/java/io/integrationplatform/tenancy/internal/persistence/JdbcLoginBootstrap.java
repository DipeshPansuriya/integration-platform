package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The JDBC side of the one trusted pre-tenant path for humans (ADR-009 Decision 3, ADR-007 Decision 2): calls the
 * definer function {@code ipaas_login_bootstrap(issuer, subject)}. It runs with or without a bound tenant; row-level
 * security still applies to every other statement. The function returns rows only for ACTIVE users of ACTIVE tenants,
 * and (issuer, subject) is unique, so the result is at most one binding.
 */
@TenantBoundaryCrossing("ipaas_login_bootstrap")
class JdbcLoginBootstrap implements LoginBootstrap {

    private static final String BOOTSTRAP_SQL =
            "select user_id, user_status, tenant_id, tenant_code, tenant_status, time_zone"
                    + " from public.ipaas_login_bootstrap(?, ?)";

    private static final RowMapper<LoginBinding> BINDING = (rs, row) -> new LoginBinding(
            rs.getObject("user_id", UUID.class),
            rs.getString("user_status"),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("tenant_code"),
            rs.getString("tenant_status"),
            ZoneId.of(rs.getString("time_zone")));

    private final JdbcTemplate jdbc;

    JdbcLoginBootstrap(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<LoginBinding> resolve(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(BOOTSTRAP_SQL, BINDING, issuer, subject).stream().findFirst();
    }
}
