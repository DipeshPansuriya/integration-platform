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

    /** The database's direction back in the API vocabulary (BOTH has no API word yet and is echoed as is). */
    public static String directionFromDb(String dbDirection) {
        return switch (dbDirection) {
            case "SOURCE" -> "INBOUND";
            case "TARGET" -> "OUTBOUND";
            default -> dbDirection;
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

    /**
     * E5 T-102: a registry row with its type (API, DB, MQ, FILE, or {@code null} for an E3 untyped system) and its
     * settings as JSON text (the type's schema, {@code SystemSettings}; {@code null} when untyped).
     */
    public record SystemDetail(String id, String code, String name, String direction, String type, String settings) {
    }

    private static final RowMapper<SystemDetail> SYSTEM_DETAIL = (rs, n) -> new SystemDetail(
            rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("direction"),
            rs.getString("type"), rs.getString("settings"));

    private static final String READ_DETAIL = "select s.id::text as id, s.code, s.name, s.direction, s.type,"
            + " s.settings::text as settings from public.system s";

    private static final String INSERT_TYPED_SYSTEM = "with ins as ("
            + "insert into public.system (tenant_id, code, name, direction, type, settings)"
            + " values (current_setting('app.tenant_id')::uuid, :code, :name, :direction, :type, :settings::jsonb)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String UPDATE_SYSTEM =
            "update public.system set name = :name, type = :type, settings = :settings::jsonb";

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
            + "    coalesce(:base_url::text, 'https://endpoint.invalid/'), 'POST', 'NO_AUTH', 5000)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String UPDATE_ENDPOINT_BASE_URL = "update public.target_endpoint set base_url = :base_url";

    private static final String READ_ENDPOINT_BASE_URL =
            "select te.base_url from public.target_endpoint te";

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

    /** E5 T-102: registers a typed system (settings already validated against the type's schema). */
    public String insertTypedSystem(String code, String name, String directionDb, String type, String settingsJson) {
        return first(query(INSERT_TYPED_SYSTEM, null,
                Params.of("code", code, "name", name, "direction", directionDb, "type", type,
                        "settings", settingsJson),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the system insert returned no row"));
    }

    /** One system of the bound tenant; {@code systemId} must already be a well-formed uuid. */
    public Optional<SystemDetail> findSystem(String systemId) {
        return first(query(READ_DETAIL, "s.id = :id::uuid", Params.of("id", systemId), SYSTEM_DETAIL));
    }

    /** Every system of the bound tenant (unordered; the caller sorts). */
    public List<SystemDetail> listSystems() {
        return query(READ_DETAIL, null, Params.of(), SYSTEM_DETAIL);
    }

    /** Sets name, type and settings of one system of the bound tenant; the number of rows changed (0 or 1). */
    public int updateSystem(String systemId, String name, String type, String settingsJson) {
        return update(UPDATE_SYSTEM, "id = :id::uuid",
                Params.of("id", systemId, "name", name, "type", type, "settings", settingsJson));
    }

    /** The default endpoint of an OUTBOUND system, with the placeholder base address (E3 onboarding). */
    public String insertDefaultEndpoint(String systemId, String code, String name) {
        return insertDefaultEndpoint(systemId, code, name, null);
    }

    /** As above with {@code baseUrl} (the API system's already-validated {@code settings.baseUrl}); {@code null} = placeholder. */
    public String insertDefaultEndpoint(String systemId, String code, String name, String baseUrl) {
        return first(query(INSERT_ENDPOINT, null,
                Params.of("system_id", systemId, "code", code, "name", name, "base_url", baseUrl),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the target endpoint insert returned no row"));
    }

    /** The delivery address now held by the system's endpoint (bound tenant only); empty when it has none. */
    public Optional<String> findEndpointBaseUrl(String systemId) {
        return first(query(READ_ENDPOINT_BASE_URL, "te.system_id = :system_id::uuid",
                Params.of("system_id", systemId), (rs, n) -> rs.getString("base_url")));
    }

    /**
     * Keeps the delivery address of a system's endpoint in step with its saved {@code settings.baseUrl} (the address
     * the destination guard already accepted at save); tenant-scoped, the number of endpoint rows changed.
     */
    public int updateEndpointBaseUrl(String systemId, String baseUrl) {
        return update(UPDATE_ENDPOINT_BASE_URL, "system_id = :system_id::uuid",
                Params.of("system_id", systemId, "base_url", baseUrl));
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
