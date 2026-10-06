package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Systems and their target endpoints (T-015 onboarding premise: a flow's destination is an OUTBOUND system's
 * registered endpoint). Registering is rows only - the endpoint created beside an OUTBOUND system carries the
 * schema's defaults except the placeholders the contract demands ({@code base_url}, method, auth, timeout); E5's
 * connectivity work replaces them through the same table, never this API.
 */
public final class JdbcSystemRepository extends TenantScopedRepository {

    /** The direction vocabulary of the API: the database's closed set is SOURCE/TARGET/BOTH (V2_2_1). */
    public static String directionToDb(String apiDirection) {
        return switch (apiDirection) {
            case "INBOUND" -> "SOURCE";
            case "OUTBOUND" -> "TARGET";
            default -> throw new IllegalArgumentException("unknown direction " + apiDirection);
        };
    }

    public JdbcSystemRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, failClosed(tenantContexts));
    }

    private static TenantContext failClosed(ObjectProvider<TenantContext> tenantContexts) {
        return Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty());
    }

    public record SystemRow(String id, String code, String name, String direction) {
    }

    private static final RowMapper<SystemRow> SYSTEM_ROW = (rs, n) -> new SystemRow(
            rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("direction"));

    private static final String READ_SYSTEM =
            "select s.id::text as id, s.code, s.name, s.direction from public.system s";

    private static final String INSERT_SYSTEM = "with ins as ("
            + "insert into public.system (tenant_id, code, name, direction)"
            + " values (current_setting('app.tenant_id')::uuid, :code, :name, :direction)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String INSERT_ENDPOINT = "with ins as ("
            + "insert into public.target_endpoint (tenant_id, system_id, code, name, base_url,"
            + "    http_method, auth_method, timeout_ms)"
            + " values (current_setting('app.tenant_id')::uuid, :system_id::uuid, :code, :name,"
            + "    'https://endpoint.invalid/', 'POST', 'NO_AUTH', 5000)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_ENDPOINT_OF_SYSTEM =
            "select te.id::text as id from public.target_endpoint te";

    public Optional<SystemRow> findSystemByCode(String code) {
        return first(query(READ_SYSTEM, "s.code = :code", Params.of("code", code), SYSTEM_ROW));
    }

    public String insertSystem(String code, String name, String directionDb) {
        return first(query(INSERT_SYSTEM, null,
                Params.of("code", code, "name", name, "direction", directionDb),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the system insert returned no row"));
    }

    public String insertDefaultEndpoint(String systemId, String code, String name) {
        return first(query(INSERT_ENDPOINT, null, Params.of("system_id", systemId, "code", code, "name", name),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the target endpoint insert returned no row"));
    }

    /** The endpoint a route points at for this system (E3: one default endpoint per registered system). */
    public Optional<String> findEndpointOfSystem(String systemId) {
        return first(query(READ_ENDPOINT_OF_SYSTEM, "te.system_id = :system_id::uuid",
                Params.of("system_id", systemId), (rs, n) -> rs.getString("id")));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
