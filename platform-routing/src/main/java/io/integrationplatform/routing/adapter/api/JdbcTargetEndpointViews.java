package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The adapter's reads of {@code target_endpoint} (V2_2_1) - single-table, tenant-scoped, every fragment a
 * constant (R08/R09/R11). The error dictionary arrives as JSON ({@code {"503":"TRANSIENT"}}); an entry whose
 * value is not an outcome-class name is a configuration bug and fails the read - a wrong name would silently
 * drift the one register.
 */
public class JdbcTargetEndpointViews extends TenantScopedRepository implements TargetEndpointViews {

    private static final String SELECT_ENDPOINT = """
            select te.base_url, te.http_method, te.timeout_ms, te.auth_method, te.idempotent_receive,
                   te.error_dictionary
            from public.target_endpoint te""";

    public JdbcTargetEndpointViews(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public Optional<TargetEndpointView> byEndpointId(UUID endpointId) {
        List<TargetEndpointRow> rows = query(SELECT_ENDPOINT,
                "te.id = :id::uuid", Map.of("id", endpointId.toString()),
                (rs, rowNum) -> new TargetEndpointRow(rs.getString("base_url"), rs.getString("http_method"),
                        rs.getInt("timeout_ms"), rs.getString("auth_method"), rs.getBoolean("idempotent_receive"),
                        rs.getString("error_dictionary")));
        return rows.stream().findFirst().map(row -> new TargetEndpointView(URI.create(row.baseUrl()), row.httpMethod(),
                row.timeoutMs(), row.authMethod(), row.idempotentReceive(), dictionary(row.errorDictionary())));
    }

    private static Map<Integer, OutcomeClass> dictionary(String json) {
        Map<Integer, OutcomeClass> read = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return read;
        }
        tools.jackson.databind.JsonNode entries = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(json);
        entries.properties().forEach(entry -> {
            int status;
            try {
                status = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException malformed) {
                throw new IllegalStateException("the target endpoint's error_dictionary carries status key '"
                        + entry.getKey() + "' - not a status code");
            }
            read.put(status, OutcomeClass.valueOf(entry.getValue().asString().toUpperCase()));
        });
        return read;
    }

    private record TargetEndpointRow(String baseUrl, String httpMethod, int timeoutMs, String authMethod,
            boolean idempotentReceive, String errorDictionary) {
    }
}