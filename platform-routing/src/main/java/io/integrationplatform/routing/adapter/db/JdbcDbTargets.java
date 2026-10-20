package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;

/**
 * T-149 (E16-F1-S2) on the T-126 registry precedent: the endpoint row's DB target - the endpoint's DB system's
 * stored registry settings ({@code public.system}; the subquery pattern keeps the bare tenant predicate bound to
 * {@code target_endpoint} only) and the restricted account's password from the credential seam (opened only at
 * send time, ADR-007 Decision 7 rule 6). A row we cannot parse is a configuration bug thrown as an
 * {@link IllegalStateException}, which {@code DbTargetCall} classes PERMANENT, never transient.
 */
public class JdbcDbTargets extends TenantScopedRepository implements DbTargets {

    private static final String SELECT_DB_TARGET = "select (select s.settings::text from public.system s"
            + " where s.id = te.system_id and s.type = 'DB') as v from public.target_endpoint te";

    /** The register name of the field the vault stores for DB ({@code sealed.field()}), not an admin name. */
    public static final String DB_ACCOUNT_PASSWORD_FIELD = "DB_ACCOUNT_PASSWORD";

    /** The restricted account's password per endpoint - open only for this send; the send zeroes the copy. */
    private final Password password;

    /** One opened password per endpoint (the send path's only copy; it zeroes it after the attempt). */
    @FunctionalInterface
    public interface Password {
        Optional<char[]> forEndpoint(UUID targetEndpointId);
    }

    public JdbcDbTargets(JdbcClient jdbcClient, TenantContext tenantContext, Password password) {
        super(jdbcClient, tenantContext);
        this.password = Objects.requireNonNull(password, "password");
    }

    @Override
    public Optional<DbTarget> byEndpointId(UUID targetEndpointId) {
        return one(SELECT_DB_TARGET, targetEndpointId).map(settings -> target(targetEndpointId, settings));
    }

    private DbTarget target(UUID targetEndpointId, String settingsJson) {
        try {
            return new DbTarget(DbSettings.fromJson(settingsJson),
                    password.forEndpoint(targetEndpointId).orElse(null));
        } catch (JacksonException malformed) { // a stored row we cannot read: the class name only, never its text
            throw new IllegalStateException("the DB target settings are unusable ("
                    + malformed.getClass().getSimpleName() + ")");
        }
    }

    private Optional<String> one(String select, UUID targetEndpointId) {
        return query(select, "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()),
                (rs, rowNum) -> rs.getString("v")).stream().filter(Objects::nonNull).findFirst();
    }
}