package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The contract registry reads and writes (T-019's contract nodes, ADR-011 Decision 2): the draft-save inserts a
 * new {@code contract_version} row per save, and the submit runs the {@code config_submit_contract_version}
 * definer function (V2_2_5) because lane B's cut left {@code contract_version} without any UPDATE grant - the
 * lifecycle move belongs to the database path, mirroring {@code config_approve_version}.
 *
 * <p>E5 lane A (T-105): contracts of every ADR-011 role (V2_2_5_1), the imported version with its original OpenAPI
 * document and lineage, the listing of contracts and versions side by side, and the non-author approval through the
 * {@code config_approve_contract_version} definer function (V2_2_5_1). Every statement keeps the base's tenant
 * predicate; the system code and the contract role come from subqueries so {@code tenant_id} stays unambiguous.
 */
public final class JdbcContractRepository extends TenantScopedRepository {

    public JdbcContractRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, failClosed(tenantContexts));
    }

    private static TenantContext failClosed(ObjectProvider<TenantContext> tenantContexts) {
        return Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty());
    }

    public record ContractRow(String id, String systemId, String code, String name) {
    }

    /** One version row with its system code (the version's schema is the draft-save's {@code sourceSchema}). */
    public record VersionRow(String id, String contractId, String versionLabel, String status,
            String jsonSchema, String changeNote, String systemCode) {
    }

    /** One contract with its role and system code (T-105 listing). */
    public record ContractSummary(String id, String systemCode, String code, String role, String name) {
    }

    /** One version with everything T-105 shows: role, the original OpenAPI document, lineage, author and approver. */
    public record VersionDetail(String id, String contractId, String systemCode, String role, String versionLabel,
            String status, String jsonSchema, String openapiDocument, String derivedFromVersionId,
            String changeNote, String authorId, String approverId, Instant createdAt) {
    }

    private static final RowMapper<ContractRow> CONTRACT_ROW = (rs, n) -> new ContractRow(
            rs.getString("id"), rs.getString("system_id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<VersionRow> VERSION_ROW = (rs, n) -> new VersionRow(
            rs.getString("id"), rs.getString("contract_id"), rs.getString("version_label"),
            rs.getString("status"), rs.getString("json_schema"),
            rs.getString("change_note"), rs.getString("system_code"));

    private static final RowMapper<ContractSummary> CONTRACT_SUMMARY = (rs, n) -> new ContractSummary(
            rs.getString("id"), rs.getString("system_code"), rs.getString("code"), rs.getString("role"),
            rs.getString("name"));

    private static final RowMapper<VersionDetail> VERSION_DETAIL = (rs, n) -> new VersionDetail(
            rs.getString("id"), rs.getString("contract_id"), rs.getString("system_code"), rs.getString("role"),
            rs.getString("version_label"), rs.getString("status"), rs.getString("json_schema"),
            rs.getString("openapi_document"), rs.getString("derived_from_version_id"), rs.getString("change_note"),
            rs.getString("author_id"), rs.getString("approver_id"), rs.getTimestamp("created_at").toInstant());

    private static final String READ_CONTRACT_BY_CODE =
            "select c.id::text as id, c.system_id::text as system_id, c.code, c.name from public.contract c";

    private static final String INSERT_CONTRACT = "with ins as ("
            + "insert into public.contract (tenant_id, system_id, code, role, name)"
            + " values (current_setting('app.tenant_id')::uuid, :system_id::uuid, :code,"
            + "    'SOURCE_REQUEST', :name)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_VERSION_LABELS =
            "select cv.version_label from public.contract_version cv";

    private static final String INSERT_VERSION = "with ins as ("
            + "insert into public.contract_version (tenant_id, contract_id, version_label, status,"
            + "    json_schema_draft_2020_12, author_id)"
            + " values (current_setting('app.tenant_id')::uuid, :contract_id::uuid, :version_label, 'DRAFT',"
            + "    :json_schema::jsonb, :author_id::uuid)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_VERSION = "select cv.id::text as id, cv.contract_id::text as contract_id,"
            + " cv.version_label, cv.status, cv.json_schema_draft_2020_12::text as json_schema,"
            + " cv.change_note,"
            + " (select s.code from public.contract c join public.system s on s.id = c.system_id"
            + "     where c.id = cv.contract_id) as system_code"
            + " from public.contract_version cv";

    /** The definer submit: runs once, its tenant stub keeps the base's predicate honest (see V2_2_5). */
    private static final String SUBMIT_VERSION = "select result from ("
            + "    select public.config_submit_contract_version(:version_id::uuid, :change_note) as result,"
            + "    current_setting('app.tenant_id')::uuid as tenant_id) called";

    private static final String READ_CONTRACT_SUMMARY = "select c.id::text as id, c.code, c.role, c.name,"
            + " (select s.code from public.system s where s.id = c.system_id) as system_code"
            + " from public.contract c";

    private static final String INSERT_CONTRACT_WITH_ROLE = "with ins as ("
            + "insert into public.contract (tenant_id, system_id, code, role, name)"
            + " values (current_setting('app.tenant_id')::uuid, :system_id::uuid, :code, :role, :name)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String INSERT_IMPORTED_VERSION = "with ins as ("
            + "insert into public.contract_version (tenant_id, contract_id, version_label, status,"
            + "    json_schema_draft_2020_12, openapi_document, derived_from_version_id, author_id)"
            + " values (current_setting('app.tenant_id')::uuid, :contract_id::uuid, :version_label, 'DRAFT',"
            + "    :json_schema::jsonb, :openapi_document::jsonb, :derived_from::uuid, :author_id::uuid)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_VERSION_DETAIL = "select cv.id::text as id,"
            + " cv.contract_id::text as contract_id, cv.version_label, cv.status,"
            + " cv.json_schema_draft_2020_12::text as json_schema, cv.openapi_document::text as openapi_document,"
            + " cv.derived_from_version_id::text as derived_from_version_id, cv.change_note,"
            + " cv.author_id::text as author_id, cv.approver_id::text as approver_id, cv.created_at,"
            + " (select s.code from public.contract c join public.system s on s.id = c.system_id"
            + "     where c.id = cv.contract_id) as system_code,"
            + " (select c.role from public.contract c where c.id = cv.contract_id) as role"
            + " from public.contract_version cv";

    /** The definer approval (V2_2_5_1): the bound tenant's PENDING_APPROVAL row, never by its author. */
    private static final String APPROVE_VERSION = "select result from ("
            + "    select public.config_approve_contract_version(:version_id::uuid, :approver_id::uuid) as result,"
            + "    current_setting('app.tenant_id')::uuid as tenant_id) called";

    public Optional<ContractRow> findContractByCode(String code) {
        return first(query(READ_CONTRACT_BY_CODE, "c.code = :code", Params.of("code", code), CONTRACT_ROW));
    }

    public String insertContract(String systemId, String code, String name) {
        return first(query(INSERT_CONTRACT, null, Params.of("system_id", systemId, "code", code, "name", name),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the contract insert returned no row"));
    }

    public List<String> listVersionLabels(String contractId) {
        return query(READ_VERSION_LABELS, "cv.contract_id = :contract_id::uuid",
                Params.of("contract_id", contractId), (rs, n) -> rs.getString(1));
    }

    public List<VersionRow> listVersionsOfContract(String contractId) {
        return query(READ_VERSION, "cv.contract_id = :contract_id::uuid",
                Params.of("contract_id", contractId), VERSION_ROW);
    }

    public String insertVersion(String contractId, String versionLabel, String jsonSchema, String authorId) {
        return first(query(INSERT_VERSION, null,
                Params.of("contract_id", contractId, "version_label", versionLabel,
                        "json_schema", jsonSchema, "author_id", authorId),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the contract version insert returned no row"));
    }

    public Optional<VersionRow> findVersionById(String versionId) {
        return first(query(READ_VERSION, "cv.id = :id::uuid", Params.of("id", versionId), VERSION_ROW));
    }

    public String submitVersion(String versionId, String changeNote) {
        return first(query(SUBMIT_VERSION, null,
                Params.of("version_id", versionId, "change_note", changeNote),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the contract submit function returned no row"));
    }

    // ------------------------------------------------------------------ E5 lane A (T-105, T-106)

    /** The bound tenant's contract of one system in one role. */
    public Optional<ContractSummary> findContract(String systemId, String role) {
        return first(query(READ_CONTRACT_SUMMARY, "c.system_id = :system_id::uuid and c.role = :role",
                Params.of("system_id", systemId, "role", role), CONTRACT_SUMMARY));
    }

    public Optional<ContractSummary> findContractById(String contractId) {
        return first(query(READ_CONTRACT_SUMMARY, "c.id = :id::uuid", Params.of("id", contractId),
                CONTRACT_SUMMARY));
    }

    /** Every contract of the bound tenant, ordered by system code, role and code. */
    public List<ContractSummary> listContracts() {
        return query(READ_CONTRACT_SUMMARY, null, Params.of(), CONTRACT_SUMMARY).stream()
                .sorted(Comparator.comparing(ContractSummary::systemCode)
                        .thenComparing(ContractSummary::role)
                        .thenComparing(ContractSummary::code))
                .toList();
    }

    public String insertContract(String systemId, String code, String role, String name) {
        return first(query(INSERT_CONTRACT_WITH_ROLE, null,
                Params.of("system_id", systemId, "code", code, "role", role, "name", name),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the contract insert returned no row"));
    }

    public String insertImportedVersion(String contractId, String versionLabel, String jsonSchema,
            String openapiDocument, String derivedFromVersionId, String authorId) {
        return first(query(INSERT_IMPORTED_VERSION, null,
                Params.of("contract_id", contractId, "version_label", versionLabel, "json_schema", jsonSchema,
                        "openapi_document", openapiDocument, "derived_from", derivedFromVersionId,
                        "author_id", authorId),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the contract version insert returned no row"));
    }

    public Optional<VersionDetail> findVersionDetail(String versionId) {
        return first(query(READ_VERSION_DETAIL, "cv.id = :id::uuid", Params.of("id", versionId), VERSION_DETAIL));
    }

    /** The versions of one contract, or of every contract of the tenant when {@code contractId} is null. */
    public List<VersionDetail> listVersionDetails(String contractId) {
        return contractId == null
                ? query(READ_VERSION_DETAIL, null, Params.of(), VERSION_DETAIL)
                : query(READ_VERSION_DETAIL, "cv.contract_id = :contract_id::uuid",
                        Params.of("contract_id", contractId), VERSION_DETAIL);
    }

    /**
     * The definer approval's answer: {@code APPROVED}, {@code VERSION_NOT_FOUND}, {@code REFUSED_AUTHOR_IS_APPROVER}
     * or {@code REFUSED_<status>} for a version that is not pending approval.
     */
    public String approveVersion(String versionId, String approverId) {
        return first(query(APPROVE_VERSION, null, Params.of("version_id", versionId, "approver_id", approverId),
                (RowMapper<String>) (rs, n) -> rs.getString(1)))
                .orElseThrow(() -> new IllegalStateException("the contract approve function returned no row"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
