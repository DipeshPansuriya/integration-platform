package io.integrationplatform.routing.templates;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@link RouteConfigReader} over the real configuration rows (T-116, ADR-010 section 3): an interface's ACTIVE
 * {@code route} rows, each with its target endpoint's system type (the route template's target type), the endpoint's
 * base URL (the URI {@code allowedSchemes} guards) and the versions the row pins - the target contract version of the
 * route's mapping and the {@code mapping_version} whose content equals that mapping (the same match the config
 * module's flow reads use). Read inside the publish transaction, so the rows the publish just wrote are the ones
 * instantiated. Tenant-scoped (R08/R11): only {@code route} is in the FROM clause, every other table is reached by
 * a sub-select under its own row-level security, every fragment a constant.
 */
public class JdbcRouteConfigReader extends TenantScopedRepository implements RouteConfigReader {

    private static final String SELECT_ROUTES = """
            select r.id, r.target_endpoint_id,
                   (select s.type from public.target_endpoint te join public.system s on s.id = te.system_id
                     where te.id = r.target_endpoint_id) as target_type,
                   (select te.base_url from public.target_endpoint te
                     where te.id = r.target_endpoint_id) as endpoint_uri,
                   (select m.target_contract_version_id from public.mapping m
                     where m.route_id = r.id) as contract_version_id,
                   (select v.id from public.mapping m join public.mapping_version v
                       on v.tenant_id = m.tenant_id and v.mapping_id = m.id
                      and v.source_contract_version_id is not distinct from m.source_contract_version_id
                      and v.target_contract_version_id is not distinct from m.target_contract_version_id
                      and v.field_mappings = m.field_mappings and v.compiled_expression = m.compiled_expression
                     where m.route_id = r.id limit 1) as mapping_version_id
            from public.route r""";

    public JdbcRouteConfigReader(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /**
     * The bound tenant scopes the read (the tenant predicate plus row-level security), never {@code tenantId}: the
     * publish announces that same binding, and no value carried by an event can widen a read past the bound tenant.
     */
    @Override
    public List<RouteConfigRow> activeRoutes(UUID tenantId, UUID interfaceId) {
        return query(SELECT_ROUTES, "r.interface_id = :interface_id::uuid and r.status = 'ACTIVE'",
                Map.of("interface_id", interfaceId.toString()),
                (rs, rowNum) -> new RouteConfigRow(rs.getObject("id", UUID.class),
                        rs.getObject("target_endpoint_id", UUID.class), rs.getString("target_type"),
                        rs.getString("endpoint_uri"), rs.getObject("contract_version_id", UUID.class),
                        rs.getObject("mapping_version_id", UUID.class)));
    }
}
