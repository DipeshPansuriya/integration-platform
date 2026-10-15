package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T81 / B9: the names the routes listing shows for a route - its target endpoint's code and name and the endpoint's
 * system code. A route has no name column of its own; the designer names it by where it delivers. One tenant-scoped
 * read (the base appends the bound tenant's predicate; RLS fails closed behind it), keeping the module's
 * single-outer-table rule: the system code arrives as a correlated subselect, never a join.
 */
public final class JdbcFlowRouteNames extends TenantScopedRepository {

    /** One target endpoint as the routes listing names it. */
    public record EndpointRow(String id, String code, String name, String systemCode) {
    }

    private static final String READ_ENDPOINTS = "select te.id::text as id, te.code, te.name,"
            + " (select s.code from public.system s where s.id = te.system_id) as system_code"
            + " from public.target_endpoint te";

    private static final RowMapper<EndpointRow> ENDPOINT_ROW = (rs, n) -> new EndpointRow(
            rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("system_code"));

    public JdbcFlowRouteNames(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty()));
    }

    /** The bound tenant's endpoints among {@code endpointIds}, by id; an id of another tenant is simply absent. */
    public Map<String, EndpointRow> findEndpoints(Collection<String> endpointIds) {
        Map<String, EndpointRow> byId = new LinkedHashMap<>();
        if (endpointIds.isEmpty()) {
            return byId;
        }
        List<EndpointRow> rows = query(READ_ENDPOINTS, "te.id::text in (:ids)",
                Params.of("ids", List.copyOf(endpointIds)), ENDPOINT_ROW);
        rows.forEach(row -> byId.put(row.id(), row));
        return byId;
    }
}
