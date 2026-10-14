package io.integrationplatform.config.mapping.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

/**
 * The mapping side of the configuration persistence (T-190): the live {@code mapping} row of a route, its
 * content-addressed {@code mapping_version} rows (ADR-013) and the contract facts the save validates against.
 * House rule holds - one outer table per statement, other tables' facts as correlated subselects, every
 * statement tenant-scoped by {@link TenantScopedRepository}.
 */
public class JdbcMappingRepository extends TenantScopedRepository {

    public JdbcMappingRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, failClosed(tenantContexts));
    }

    private static TenantContext failClosed(ObjectProvider<TenantContext> tenantContexts) {
        return Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty());
    }

    /** The live mapping of one route, its bindings and its current content version (null when unbound). */
    public record LiveMapping(String id, String fieldMappingsJson, String compiledExpression,
            String sourceContractVersionId, String targetContractVersionId, String mappingVersionId) {
    }

    /** A contract version's pin-facing facts, read with correlated subselects (no joins). */
    public record ContractVersionFacts(String id, String versionLabel, String status, String role,
            String systemId, String systemCode) {
    }

    /**
     * The mapping_version row's facts for the publish pin check: content, pins, engine, target version's status, and
     * the mapping the row belongs to (T-037: the sample-run gate keys on it with the content hash).
     */
    public record VersionFacts(String id, int version, String engine, String sourceId, String targetId,
            String fieldMappingsText, String compiledExpression, byte[] contentSha256, String targetStatus,
            String targetRole, String targetSystemId, String mappingId) {
    }

    /** The (mapping_id, version) pair after minting. */
    public record LiveMappingVersionRef(String id, int version) {
    }

    private static final String READ_MAPPING_OF_ROUTE = "select m.id::text as id,"
            + " m.field_mappings::text as field_mappings, m.compiled_expression,"
            + " m.source_contract_version_id::text as source_contract_version_id,"
            + " m.target_contract_version_id::text as target_contract_version_id,"
            + " (select v.id::text from public.mapping_version v"
            + "     where v.tenant_id = m.tenant_id and v.mapping_id = m.id"
            + "       and v.source_contract_version_id is not distinct from m.source_contract_version_id"
            + "       and v.target_contract_version_id is not distinct from m.target_contract_version_id"
            + "       and v.field_mappings = m.field_mappings and v.compiled_expression = m.compiled_expression"
            + "     limit 1) as mapping_version_id"
            + " from public.mapping m";

    private static final String UPDATE_MAPPING_OF_ROUTE = "update public.mapping"
            + " set field_mappings = :field_mappings::jsonb, compiled_expression = :compiled_expression,"
            + " source_contract_version_id = :source_contract_version_id::uuid,"
            + " target_contract_version_id = :target_contract_version_id::uuid, updated_at = now()";

    private static final String READ_VERSION_OF_CONTENT = "select v.id::text as id, v.version"
            + " from public.mapping_version v";

    private static final String INSERT_VERSION = "with ins as ("
            + "insert into public.mapping_version"
            + "    (tenant_id, mapping_id, version, source_contract_version_id, target_contract_version_id,"
            + "     engine, field_mappings, compiled_expression, content_sha256, author_id)"
            + " values (current_setting('app.tenant_id')::uuid, :mapping_id::uuid, :version::integer,"
            + "    :source_contract_version_id::uuid, :target_contract_version_id::uuid, :engine,"
            + "    :field_mappings::jsonb, :compiled_expression, :content_sha256, :author_id::uuid)"
            + " on conflict (tenant_id, mapping_id, content_sha256) do nothing"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_NEXT_VERSION = "select coalesce(max(v.version), 0) + 1 as next"
            + " from public.mapping_version v";

    private static final String READ_VERSION_BY_ID = "select v.id::text as id, v.mapping_id::text as mapping_id,"
            + " v.version, v.engine,"
            + " v.source_contract_version_id::text as source_id, v.target_contract_version_id::text as target_id,"
            + " v.field_mappings::text as field_mappings, v.compiled_expression, v.content_sha256,"
            + " (select cv.status from public.contract_version cv where cv.id = v.target_contract_version_id)"
            + "     as target_status,"
            + " (select c.role from public.contract c"
            + "     where c.id = (select cv.contract_id from public.contract_version cv"
            + "                    where cv.id = v.target_contract_version_id)) as target_role,"
            + " (select c.system_id::text from public.contract c"
            + "     where c.id = (select cv.contract_id from public.contract_version cv"
            + "                    where cv.id = v.target_contract_version_id)) as target_system_id"
            + " from public.mapping_version v";

    private static final String READ_CONTRACT_VERSION_FACTS = "select v.id::text as id, v.version_label,"
            + " v.status,"
            + " (select c.role from public.contract c where c.id = v.contract_id) as role,"
            + " (select c.system_id::text from public.contract c where c.id = v.contract_id) as system_id,"
            + " (select s.code from public.system s"
            + "     where s.id = (select c.system_id from public.contract c where c.id = v.contract_id))"
            + "     as system_code"
            + " from public.contract_version v";

    private static final String READ_SYSTEM_OF_ENDPOINT = "select te.system_id::text as system_id"
            + " from public.target_endpoint te";

    private static final String READ_SCHEMA_OF_VERSION = "select v.json_schema_draft_2020_12::text as json_schema"
            + " from public.contract_version v";

    private static final RowMapper<LiveMapping> LIVE_MAPPING = (rs, n) -> new LiveMapping(
            rs.getString("id"), rs.getString("field_mappings"), rs.getString("compiled_expression"),
            rs.getString("source_contract_version_id"), rs.getString("target_contract_version_id"),
            rs.getString("mapping_version_id"));

    private static final RowMapper<ContractVersionFacts> FACTS = (rs, n) -> new ContractVersionFacts(
            rs.getString("id"), rs.getString("version_label"), rs.getString("status"), rs.getString("role"),
            rs.getString("system_id"), rs.getString("system_code"));

    private static final RowMapper<LiveMappingVersionRef> VERSION_REF = (rs, n) -> new LiveMappingVersionRef(
            rs.getString("id"), rs.getInt("version"));

    /** The live mapping of a route, or empty for an unknown route. */
    public Optional<LiveMapping> findMappingOfRoute(String routeId) {
        return first(query(READ_MAPPING_OF_ROUTE, "m.route_id = :route_id::uuid",
                of("route_id", routeId), LIVE_MAPPING));
    }

    /** Updates the live row's content and bindings (null ids unbind); 1 when the route is the bound tenant's. */
    public int updateMappingOfRoute(String routeId, String fieldMappingsJson, String compiledExpression,
            String sourceContractVersionId, String targetContractVersionId) {
        return update(UPDATE_MAPPING_OF_ROUTE, "route_id = :route_id::uuid",
                of("route_id", routeId, "field_mappings", fieldMappingsJson, "compiled_expression",
                        compiledExpression, "source_contract_version_id", sourceContractVersionId,
                        "target_contract_version_id", targetContractVersionId));
    }

    /** The version row of exactly this content (bijective with content: mapping_version_content_unique). */
    public Optional<LiveMappingVersionRef> findVersionOfContent(String mappingId, String sourceId,
            String targetId, String fieldMappingsJson, String compiledExpression) {
        return first(query(READ_VERSION_OF_CONTENT, """
                v.mapping_id = :mapping_id::uuid
                  and v.source_contract_version_id = :source_contract_version_id::uuid
                  and v.target_contract_version_id = :target_contract_version_id::uuid
                  and v.field_mappings = :field_mappings::jsonb
                  and v.compiled_expression = :compiled_expression
                """, of("mapping_id", mappingId, "source_contract_version_id", sourceId,
                "target_contract_version_id", targetId, "field_mappings", fieldMappingsJson,
                "compiled_expression", compiledExpression), VERSION_REF));
    }

    /** max(version) + 1 for the mapping's chain. */
    public int nextVersionOfMapping(String mappingId) {
        return query(READ_NEXT_VERSION, "v.mapping_id = :mapping_id::uuid", of("mapping_id", mappingId),
                (rs, n) -> rs.getInt("next")).stream().findFirst().orElse(1);
    }

    /**
     * Mints the version row; deduplicated by content - no row when the same content already exists (ADR-013
     * Decision 2). The caller re-reads the deduplicated row. version is max + 1; a contention on the same
     * (mapping_id, version) key surfaces at the unique key, the mapping_version_number_unique serialization point.
     */
    public Optional<String> insertVersion(String mappingId, int version, String sourceId, String targetId,
            String engine, String fieldMappingsJson, String compiledExpression, byte[] contentSha256,
            String authorId) {
        List<String> ids = query(INSERT_VERSION, null,
                of("mapping_id", mappingId, "version", version, "source_contract_version_id", sourceId,
                        "target_contract_version_id", targetId, "engine", engine, "field_mappings",
                        fieldMappingsJson, "compiled_expression", compiledExpression, "content_sha256",
                        contentSha256, "author_id", authorId),
                (rs, n) -> rs.getString("id"));
        return ids.stream().findFirst();
    }

    /** A version row's facts for the publish pin check (ADR-013 Decision 5). */
    public Optional<VersionFacts> findVersionById(String versionId) {
        return query(READ_VERSION_BY_ID, "v.id = :id::uuid", of("id", versionId), (rs, n) -> new VersionFacts(
                rs.getString("id"), rs.getInt("version"), rs.getString("engine"), rs.getString("source_id"),
                rs.getString("target_id"), rs.getString("field_mappings"), rs.getString("compiled_expression"),
                rs.getBytes("content_sha256"), rs.getString("target_status"), rs.getString("target_role"),
                rs.getString("target_system_id"), rs.getString("mapping_id"))).stream().findFirst();
    }

    /** A contract version's facts (role, owning system, status, label). */
    public Optional<ContractVersionFacts> findContractVersionFacts(String versionId) {
        return first(query(READ_CONTRACT_VERSION_FACTS, "v.id = :id::uuid", of("id", versionId), FACTS));
    }

    /** The approved TARGET_REQUEST versions of one system, for naming the route's expected target pin. */
    public List<ContractVersionFacts> findApprovedTargetsOfSystem(String systemId) {
        return query(READ_CONTRACT_VERSION_FACTS, """
                v.status = 'APPROVED'
                  and (select c.role from public.contract c where c.id = v.contract_id) = 'TARGET_REQUEST'
                  and (select c.system_id from public.contract c where c.id = v.contract_id)
                      = :system_id::uuid
                """, of("system_id", systemId), FACTS);
    }

    /** The system an endpoint delivers to. */
    public Optional<String> findSystemOfEndpoint(String endpointId) {
        return first(query(READ_SYSTEM_OF_ENDPOINT, "te.id = :id::uuid", of("id", endpointId),
                (rs, n) -> rs.getString("system_id")));
    }

    /** The target contract's JSON schema (the field universe its links must stay inside, ADR-004 rule 4). */
    public Optional<String> findSchemaOfVersion(String versionId) {
        return first(query(READ_SCHEMA_OF_VERSION, "v.id = :id::uuid", of("id", versionId),
                (rs, n) -> rs.getString("json_schema")));
    }

    private static Map<String, Object> of(Object... keyValuePairs) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int position = 0; position < keyValuePairs.length; position += 2) {
            parameters.put(String.valueOf(keyValuePairs[position]),
                    keyValuePairs[position + 1] instanceof UUID uuid ? uuid.toString() : keyValuePairs[position + 1]);
        }
        return parameters;
    }

    private <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }
}