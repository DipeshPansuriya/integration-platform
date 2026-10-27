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
 * The flow (interface), its routes and mappings, and the version rows (T-015/T-019/T-021). Every read keeps the
 * base's single-outer-table rule: multi-table facts arrive as correlated subselects, never as joins whose extra
 * {@code tenant_id} would make the appended predicate ambiguous. The lifecycle's two database paths run through
 * the definer functions ({@code config_approve_version} V2_2_4, kept by lane B; the submit walk is the app's own
 * narrow UPDATE), called through the same one-row stub shape.
 */
public final class JdbcFlowRepository extends TenantScopedRepository {

    public JdbcFlowRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, failClosed(tenantContexts));
    }

    private static TenantContext failClosed(ObjectProvider<TenantContext> tenantContexts) {
        return Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty());
    }

    // ------------------------------------------------------------------ row records

    public record InterfaceRow(String id, String code, String name, String ordering, String status) {
    }

    public record DestinationRow(int sequence, String systemCode) {
    }

    public record RouteRow(String id, int sequence, String condition, boolean isPrimary, String status,
            String targetEndpointId) {
    }

    public record MappingRow(String id, String routeId, String fieldMappingsJson, String compiledExpression,
            String sourceContractVersionId, String targetContractVersionId, String mappingVersionId) {
    }

    public record VersionInfo(String id, int version, String status, String businessKeyRule, String snapshotJson,
            String sourceContractVersionId, String sourceSystem) {
    }

    // ------------------------------------------------------------------ sql (compile-time constants, R11)

    private static final String READ_INTERFACE = "select i.id::text as id, i.code, i.name, i.ordering, i.status"
            + " from public.interface i";

    private static final String INSERT_INTERFACE = "with ins as ("
            + "insert into public.interface (tenant_id, code, name, ordering)"
            + " values (current_setting('app.tenant_id')::uuid, :code, :name, :ordering)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_DESTINATIONS = "select r.sequence as sequence,"
            + " (select s.code from public.target_endpoint te join public.system s on s.id = te.system_id"
            + "     where te.id = r.target_endpoint_id) as system_code"
            + " from public.route r";

    private static final String READ_ENDPOINT_SYSTEMS = "select 0 as sequence,"
            + " (select s.code from public.system s where s.id = te.system_id) as system_code"
            + " from public.target_endpoint te";

    private static final String READ_ROUTES = "select r.id::text as id, r.sequence, r.condition,"
            + " r.is_primary, r.status, r.target_endpoint_id::text as target_endpoint_id from public.route r";

    private static final String READ_MAPPINGS = "select m.id::text as id, m.route_id::text as route_id,"
            + " m.field_mappings::text as field_mappings, m.compiled_expression,"
            + " m.source_contract_version_id::text as source_contract_version_id,"
            + " m.target_contract_version_id::text as target_contract_version_id,"
            + " (select v.id::text from public.mapping_version v"
            + "     where v.tenant_id = m.tenant_id and v.mapping_id = m.id"
            + "       and v.source_contract_version_id is not distinct from m.source_contract_version_id"
            + "       and v.target_contract_version_id is not distinct from m.target_contract_version_id"
            + "       and v.field_mappings = m.field_mappings and v.compiled_expression = m.compiled_expression"
            + "     limit 1) as mapping_version_id from public.mapping m";

    private static final String MAPPINGS_OF_INTERFACE =
            "m.route_id in (select r.id from public.route r where r.interface_id = :interface_id::uuid)";

    private static final String INSERT_ROUTE = "with ins as ("
            + "insert into public.route (tenant_id, interface_id, target_endpoint_id, sequence)"
            + " values (current_setting('app.tenant_id')::uuid, :interface_id::uuid,"
            + "    :endpoint_id::uuid, :sequence::integer)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String INSERT_ROUTE_FROM_SNAPSHOT = "with ins as ("
            + "insert into public.route (id, tenant_id, interface_id, target_endpoint_id, sequence,"
            + "    condition, is_primary, status)"
            + " values (:id::uuid, current_setting('app.tenant_id')::uuid, :interface_id::uuid,"
            + "    :endpoint_id::uuid, :sequence::integer, :condition::text, :is_primary, :status)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String INSERT_MAPPING = "with ins as ("
            + "insert into public.mapping (tenant_id, route_id, compiled_expression)"
            + " values (current_setting('app.tenant_id')::uuid, :route_id::uuid, :compiled_expression)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String INSERT_MAPPING_FROM_SNAPSHOT = "with ins as ("
            + "insert into public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression,"
            + "    source_contract_version_id, target_contract_version_id)"
            + " values (:id::uuid, current_setting('app.tenant_id')::uuid, :route_id::uuid,"
            + "    :field_mappings::jsonb, :compiled_expression, :source_contract_version_id::uuid,"
            + "    :target_contract_version_id::uuid)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String UPDATE_ROUTE_FROM_SNAPSHOT = "update public.route set sequence = :sequence::integer,"
            + " condition = :condition::text, is_primary = :is_primary, status = :status, updated_at = now()";

    private static final String UPDATE_MAPPING_FROM_SNAPSHOT =
            "update public.mapping set field_mappings = :field_mappings::jsonb,"
                    + " compiled_expression = :compiled_expression,"
                    + " source_contract_version_id = :source_contract_version_id::uuid,"
                    + " target_contract_version_id = :target_contract_version_id::uuid, updated_at = now()";

    private static final String ROUTE_EXISTS = "select 1 as present from public.route t";

    private static final String MAPPING_EXISTS = "select 1 as present from public.mapping t";

    private static final String READ_VERSIONS = "select v.id::text as id, v.version, v.status,"
            + " v.business_key_rule, v.snapshot::text as snapshot, v.author_id::text as author_id,"
            + " v.source_contract_version_id::text as source_contract_version_id,"
            + " (select s.code from public.contract_version cv join public.contract c on c.id = cv.contract_id"
            + "     join public.system s on s.id = c.system_id"
            + "     where cv.id = v.source_contract_version_id) as source_system"
            + " from public.interface_version v";

    private static final String INSERT_VERSION = "with ins as ("
            + "insert into public.interface_version (tenant_id, interface_id, version, status,"
            + "    business_key_rule, snapshot, source_contract_version_id, author_id)"
            + " values (current_setting('app.tenant_id')::uuid, :interface_id::uuid, :version::integer, 'DRAFT',"
            + "    :business_key_rule, :snapshot::jsonb, :source_contract_version_id::uuid, :author_id::uuid)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String SUBMIT_VERSION = "update public.interface_version set status = 'PENDING_APPROVAL',"
            + " change_note = :change_note";

    private static final String ARCHIVE_PUBLISHED_VERSIONS = "update public.interface_version set status = 'ARCHIVED'";

    private static final String PUBLISH_VERSION = "update public.interface_version set status = 'PUBLISHED'";

    private static final String UPDATE_INTERFACE_AFTER_DRAFT =
            "update public.interface set current_version = :version::integer, updated_at = now()";

    private static final String UPDATE_INTERFACE_ORDERING =
            "update public.interface set ordering = :ordering, updated_at = now()";

    private static final String UPDATE_INTERFACE_AFTER_PUBLISH = "update public.interface"
            + " set published_version = :version::integer,"
            + " current_version = greatest(current_version, :version::integer),"
            + " status = 'PUBLISHED', updated_at = now()";

    /** Definer approval (V2_2_4): one row, the function's text verdict, kept inside the tenant predicate. */
    private static final String APPROVE_VERSION = "select result from ("
            + "    select public.config_approve_version(:version_id::uuid, :approver_id::uuid) as result,"
            + "    current_setting('app.tenant_id')::uuid as tenant_id) called";

    // ------------------------------------------------------------------ mappers

    private static final RowMapper<InterfaceRow> INTERFACE_ROW = (rs, n) -> new InterfaceRow(
            rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("ordering"),
            rs.getString("status"));

    private static final RowMapper<DestinationRow> DESTINATION_ROW = (rs, n) -> new DestinationRow(
            rs.getInt("sequence"), rs.getString("system_code"));

    private static final RowMapper<RouteRow> ROUTE_ROW = (rs, n) -> new RouteRow(
            rs.getString("id"), rs.getInt("sequence"), rs.getString("condition"), rs.getBoolean("is_primary"),
            rs.getString("status"), rs.getString("target_endpoint_id"));

    private static final RowMapper<MappingRow> MAPPING_ROW = (rs, n) -> new MappingRow(
            rs.getString("id"), rs.getString("route_id"), rs.getString("field_mappings"),
            rs.getString("compiled_expression"), rs.getString("source_contract_version_id"),
            rs.getString("target_contract_version_id"), rs.getString("mapping_version_id"));

    private static final RowMapper<VersionInfo> VERSION_ROW = (rs, n) -> new VersionInfo(
            rs.getString("id"), rs.getInt("version"), rs.getString("status"), rs.getString("business_key_rule"),
            rs.getString("snapshot"), rs.getString("source_contract_version_id"), rs.getString("source_system"));

    private static final RowMapper<String> FIRST_COLUMN = (rs, n) -> rs.getString(1);

    // ------------------------------------------------------------------ interface rows

    public Optional<InterfaceRow> findInterface(String interfaceId) {
        return first(query(READ_INTERFACE, "i.id = :id::uuid", Params.of("id", interfaceId), INTERFACE_ROW));
    }

    public List<InterfaceRow> listInterfaces() {
        return query(READ_INTERFACE, null, Params.of(), INTERFACE_ROW);
    }

    public String insertInterface(String code, String name, String ordering) {
        return orRow(query(INSERT_INTERFACE, null, Params.of("code", code, "name", name, "ordering", ordering),
                FIRST_COLUMN));
    }

    public int updateInterfaceAfterDraft(String interfaceId, int version) {
        return update(UPDATE_INTERFACE_AFTER_DRAFT, "id = :interface_id::uuid",
                Params.of("interface_id", interfaceId, "version", version));
    }

    public int updateInterfaceOrdering(String interfaceId, String ordering) {
        return update(UPDATE_INTERFACE_ORDERING, "id = :interface_id::uuid",
                Params.of("interface_id", interfaceId, "ordering", ordering));
    }

    /** T108: the interface's masked-field list as stored JSON text ({@code []} when none); empty when not visible. */
    public Optional<String> findMaskedFields(String interfaceId) {
        return first(query("select i.masked_fields::text from public.interface i", "i.id = :id::uuid",
                Params.of("id", interfaceId), FIRST_COLUMN));
    }

    /** T108: replace the interface's masked-field list ({@code maskedFieldsJson} is a JSON array text). */
    public int updateMaskedFields(String interfaceId, String maskedFieldsJson) {
        return update("update public.interface set masked_fields = :masked_fields::jsonb, updated_at = now()",
                "id = :interface_id::uuid", Params.of("interface_id", interfaceId, "masked_fields", maskedFieldsJson));
    }

    public int updateInterfaceAfterPublish(String interfaceId, int version) {
        return update(UPDATE_INTERFACE_AFTER_PUBLISH, "id = :interface_id::uuid",
                Params.of("interface_id", interfaceId, "version", version));
    }

    // ------------------------------------------------------------------ routes and mappings

    public List<DestinationRow> findDestinations(String interfaceId) {
        return query(READ_DESTINATIONS, "r.interface_id = :interface_id::uuid",
                Params.of("interface_id", interfaceId), DESTINATION_ROW);
    }

    /**
     * V6: the systems behind a set of target endpoints (the destinations of a version SNAPSHOT, whose routes may not
     * be live rows yet). The sequence is not meaningful here. Unknown ids and other tenants' endpoints return nothing.
     */
    public List<DestinationRow> findDestinationsOfEndpoints(java.util.Collection<String> endpointIds) {
        if (endpointIds.isEmpty()) {
            return List.of();
        }
        return query(READ_ENDPOINT_SYSTEMS, "te.id = any(string_to_array(:ids, ',')::uuid[])",
                Params.of("ids", String.join(",", endpointIds)), DESTINATION_ROW);
    }

    public List<RouteRow> findRoutes(String interfaceId) {
        return query(READ_ROUTES, "r.interface_id = :interface_id::uuid",
                Params.of("interface_id", interfaceId), ROUTE_ROW);
    }

    public Optional<RouteRow> findRouteByEndpoint(String interfaceId, String endpointId) {
        return first(query(READ_ROUTES,
                "r.interface_id = :interface_id::uuid and r.target_endpoint_id = :endpoint_id::uuid",
                Params.of("interface_id", interfaceId, "endpoint_id", endpointId), ROUTE_ROW));
    }

    public List<MappingRow> findMappings(String interfaceId) {
        return query(READ_MAPPINGS, MAPPINGS_OF_INTERFACE, Params.of("interface_id", interfaceId), MAPPING_ROW);
    }

    public String insertRoute(String interfaceId, String endpointId, int sequence) {
        return orRow(query(INSERT_ROUTE, null,
                Params.of("interface_id", interfaceId, "endpoint_id", endpointId, "sequence", sequence),
                FIRST_COLUMN));
    }

    public String insertMapping(String routeId, String compiledExpression) {
        return orRow(query(INSERT_MAPPING, null,
                Params.of("route_id", routeId, "compiled_expression", compiledExpression), FIRST_COLUMN));
    }

    // ------------------------------------------------------------------ the publish's snapshot materialization

    public boolean routeExists(String routeId) {
        return !query(ROUTE_EXISTS, "t.id = :id::uuid",
                Params.of("id", routeId), (rs, n) -> rs.getInt("present")).isEmpty();
    }

    public boolean mappingExists(String mappingId) {
        return !query(MAPPING_EXISTS, "t.id = :id::uuid",
                Params.of("id", mappingId), (rs, n) -> rs.getInt("present")).isEmpty();
    }

    public String insertRouteFromSnapshot(String routeId, String interfaceId, String endpointId, int sequence,
            String condition, boolean isPrimary, String status) {
        return orRow(query(INSERT_ROUTE_FROM_SNAPSHOT, null,
                Params.of("id", routeId, "interface_id", interfaceId, "endpoint_id", endpointId,
                        "sequence", sequence, "condition", condition, "is_primary", isPrimary, "status", status),
                FIRST_COLUMN));
    }

    public int updateRouteFromSnapshot(String routeId, int sequence, String condition, boolean isPrimary,
            String status) {
        return update(UPDATE_ROUTE_FROM_SNAPSHOT, "id = :id::uuid",
                Params.of("id", routeId, "sequence", sequence, "condition", condition,
                        "is_primary", isPrimary, "status", status));
    }

    public String insertMappingFromSnapshot(String mappingId, String routeId, String fieldMappingsJson,
            String compiledExpression, String sourceContractVersionId, String targetContractVersionId) {
        return orRow(query(INSERT_MAPPING_FROM_SNAPSHOT, null,
                Params.of("id", mappingId, "route_id", routeId,
                        "field_mappings", fieldMappingsJson, "compiled_expression", compiledExpression,
                        "source_contract_version_id", sourceContractVersionId,
                        "target_contract_version_id", targetContractVersionId),
                FIRST_COLUMN));
    }

    public int updateMappingFromSnapshot(String mappingId, String fieldMappingsJson, String compiledExpression,
            String sourceContractVersionId, String targetContractVersionId) {
        return update(UPDATE_MAPPING_FROM_SNAPSHOT, "id = :id::uuid",
                Params.of("id", mappingId, "field_mappings", fieldMappingsJson,
                        "compiled_expression", compiledExpression,
                        "source_contract_version_id", sourceContractVersionId,
                        "target_contract_version_id", targetContractVersionId));
    }

    // ------------------------------------------------------------------ versions

    public Optional<VersionInfo> findLatestVersion(String interfaceId) {
        return first(query(READ_VERSIONS,
                "v.interface_id = :interface_id::uuid and v.version ="
                        + " (select max(iv.version) from public.interface_version iv"
                        + "     where iv.interface_id = :interface_id::uuid)",
                Params.of("interface_id", interfaceId), VERSION_ROW));
    }

    public Optional<VersionInfo> findLatestVersionByStatus(String interfaceId, String status) {
        return first(query(READ_VERSIONS,
                "v.interface_id = :interface_id::uuid and v.status = :status and v.version ="
                        + " (select max(iv.version) from public.interface_version iv"
                        + "     where iv.interface_id = :interface_id::uuid and iv.status = :status)",
                Params.of("interface_id", interfaceId, "status", status), VERSION_ROW));
    }

    public Optional<VersionInfo> findVersionOfInterface(String interfaceId, String versionId) {
        return first(query(READ_VERSIONS,
                "v.interface_id = :interface_id::uuid and v.id = :version_id::uuid",
                Params.of("interface_id", interfaceId, "version_id", versionId), VERSION_ROW));
    }

    public String insertVersion(String interfaceId, int version, String businessKeyRule, String snapshotJson,
            String sourceContractVersionId, String authorId) {
        return orRow(query(INSERT_VERSION, null,
                Params.of("interface_id", interfaceId, "version", version,
                        "business_key_rule", businessKeyRule, "snapshot", snapshotJson,
                        "source_contract_version_id", sourceContractVersionId, "author_id", authorId),
                FIRST_COLUMN));
    }

    public int submitVersion(String versionId, String changeNote) {
        return update(SUBMIT_VERSION, "id = :id::uuid and status = 'DRAFT'",
                Params.of("id", versionId, "change_note", changeNote));
    }

    public int archivePublishedVersions(String interfaceId, String exceptVersionId) {
        return update(ARCHIVE_PUBLISHED_VERSIONS,
                "interface_id = :interface_id::uuid and status = 'PUBLISHED' and id <> :except::uuid",
                Params.of("interface_id", interfaceId, "except", exceptVersionId));
    }

    public int publishVersion(String versionId) {
        return update(PUBLISH_VERSION, "id = :id::uuid and status = 'APPROVED'", Params.of("id", versionId));
    }

    /**
     * Runs the definer approval for the bound tenant; the answer is the function's text verdict
     * ({@code APPROVED}, {@code VERSION_NOT_FOUND}, {@code REFUSED_<status>}).
     */
    public String approveVersion(String versionId, String approverId) {
        return orRow(query(APPROVE_VERSION, null,
                Params.of("version_id", versionId, "approver_id", approverId), FIRST_COLUMN));
    }

    // ------------------------------------------------------------------ helpers

    private String orRow(List<String> rows) {
        return rows.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("the insert returned no row"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
