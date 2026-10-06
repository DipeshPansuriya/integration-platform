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
 * The contract registry reads and writes (T-019's contract nodes, ADR-011 Decision 2): the draft-save inserts a
 * new {@code contract_version} row per save, and the submit runs the {@code config_submit_contract_version}
 * definer function (V2_3_2) because lane B's cut left {@code contract_version} without any UPDATE grant - the
 * lifecycle move belongs to the database path, mirroring {@code config_approve_version}.
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

    private static final RowMapper<ContractRow> CONTRACT_ROW = (rs, n) -> new ContractRow(
            rs.getString("id"), rs.getString("system_id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<VersionRow> VERSION_ROW = (rs, n) -> new VersionRow(
            rs.getString("id"), rs.getString("contract_id"), rs.getString("version_label"),
            rs.getString("status"), rs.getString("json_schema"),
            rs.getString("change_note"), rs.getString("system_code"));

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

    /** The definer submit: runs once, its tenant stub keeps the base's predicate honest (see V2_3_2). */
    private static final String SUBMIT_VERSION = "select result from ("
            + "    select public.config_submit_contract_version(:version_id::uuid, :change_note) as result,"
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

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
