package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E16 F3 (design note task 9) over the live {@code public.mapping} row of each ACTIVE DB route (ADR-013): every
 * target root segment of the mapping's {@code field_mappings} links must be a field of the pinned target contract
 * version's JSON schema {@code properties} - the per-column store the contract already carries
 * ({@code contract_version.json_schema_draft_2020_12}). The contract check of the mapping save
 * ({@code MappingAdminService#validateBindings}) re-asserted at publish time in the {@link DbTablePublishListener}
 * path: a contract approved later than the binding, or a mapping edited after it, cannot move a send outside the
 * approved contract. The schema arrives by sub-select (the tenant predicate stays unambiguous on {@code route});
 * A target version without a schema, or with none pinned, carries no columns - every mapped column then reads as
 * missing and the publish is refused (never "present"). A database error propagates - never "all present".
 */
public class JdbcDbRouteColumns extends TenantScopedRepository implements MissingContractColumns {

    /**
     * The mapped column that the pinned target contract's {@code properties} does not carry, as
     * {@code systemCode.table.column}. Roots come from the same parse the mapping save uses ({@code targetRoots}:
     * the first segment of each link's {@code target} path); the properties check is its SQL twin
     * ({@code schema -> 'properties' ? root}).
     */
    private static final String SELECT_MISSING = """
            select distinct
              (select s.code from public.target_endpoint te join public.system s on s.id = te.system_id
                where te.id = r.target_endpoint_id) || '.' ||
              (select s.settings ->> 'table' from public.target_endpoint te join public.system s on s.id = te.system_id
                where te.id = r.target_endpoint_id) || '.' || roots.root as v
            from public.route r
            cross join lateral (
              select distinct split_part(link ->> 'target', '.', 1) as root
              from public.mapping m, jsonb_array_elements(m.field_mappings) as t(link)
              where m.route_id = r.id and m.field_mappings is not null
                and jsonb_typeof(m.field_mappings) = 'array'
                and coalesce(link ->> 'target', '') <> '') roots""";

    private static final String MISSING_FOR_INTERFACE = """
            r.interface_id = :interface_id::uuid and r.status = 'ACTIVE'
            and exists (select 1 from public.target_endpoint te join public.system s on s.id = te.system_id
              where te.id = r.target_endpoint_id and s.type = 'DB' and s.settings ->> 'table' is not null)
            and not coalesce((select cv.json_schema_draft_2020_12 -> 'properties'
                from public.contract_version cv where cv.id = m.target_contract_version_id),
              '{}'::jsonb) ? roots.root::text""";

    public JdbcDbRouteColumns(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public List<String> ofInterface(UUID interfaceId) {
        return query(SELECT_MISSING, MISSING_FOR_INTERFACE, Map.of("interface_id", interfaceId.toString()),
                (rs, n) -> rs.getString("v"));
    }
}