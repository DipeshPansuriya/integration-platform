package io.integrationplatform.config.contract.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository;
import io.integrationplatform.config.contract.application.ContractDocumentImporter.Format;
import io.integrationplatform.config.contract.application.ContractDocumentImporter.ImportedSchema;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.ContractRow;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.ContractSummary;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionDetail;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemRow;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract registry rules (ADR-011 Decision 2, the T-092/T-093 nodes the lifecycle keeps): the draft-save
 * stores the {@code sourceSchema} as a new draft source contract version (one per save, the schema never
 * rewritten), and the submit moves a draft to pending approval through the V2_2_5 definer function, storing the
 * change note beside it. The flow creation asks this service for the source system's draft version id - the
 * interface version's source contract is a required composite reference (V2_2_4), so a flow's own onboarding
 * row set includes the skeleton contract version the rows-only story speaks of.
 *
 * <p>E5 lane A (T-105, ADR-011 Decisions 2 and 3): versioned source and target contracts imported from OpenAPI or
 * JSON Schema ({@link ContractDocumentImporter}), one {@code contract} per system and role, each version a row of its
 * own with its lineage ({@code derived_from} = the highest lower label of the same contract), listed side by side,
 * approved only by someone other than its author (V2_2_5_1). RETIRED versions stay readable and listed (rule 4).
 */
public class ContractAdminService {

    /** The contract roles of ADR-011 Decision 2 (V2_2_5_1's CHECK). */
    public static final List<String> ROLES =
            List.of("SOURCE_REQUEST", "SOURCE_RESPONSE", "TARGET_REQUEST", "TARGET_RESPONSE");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LABEL = Pattern.compile("^\\d+\\.\\d+$");
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JdbcContractRepository contracts;
    private final JdbcSystemRepository systems;
    private final ApplicationEventPublisher events;
    private final TenantContext tenants;
    private final ContractDocumentImporter importer = new ContractDocumentImporter();

    /** One contract with its versions in label order (T-105 listing). */
    public record ContractWithVersions(ContractSummary contract, List<VersionDetail> versions) {
    }

    /**
     * @param events  where a completed import or approval publishes its audit event (E12-F2-S1, T-134)
     * @param tenants the bound tenant the audit event names
     */
    public ContractAdminService(JdbcContractRepository contracts, JdbcSystemRepository systems,
            ApplicationEventPublisher events, TenantContext tenants) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.systems = Objects.requireNonNull(systems, "systems");
        this.events = Objects.requireNonNull(events, "events");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    // ------------------------------------------------------------------ E5 lane A (T-105)

    /**
     * POST /api/v1/admin/contracts/versions/import - a new DRAFT version of the system's contract in the given role,
     * its schema imported from an OpenAPI document or a JSON Schema. A document that does not parse is refused with
     * the parser's errors before anything is written; a label the contract already has is 409.
     */
    @Transactional
    public VersionDetail importVersion(JsonNode body, UUID actorId) {
        String systemCode = SystemAdminService.required(body, "system");
        String role = SystemAdminService.required(body, "role");
        if (!ROLES.contains(role)) {
            throw ConfigApiException.validation("role must be one of " + String.join(", ", ROLES));
        }
        String label = SystemAdminService.required(body, "versionLabel");
        if (!LABEL.matcher(label).matches()) {
            throw ConfigApiException.validation("versionLabel must be major.minor, for example 2.0");
        }
        Format format = format(SystemAdminService.required(body, "format"));
        String schemaName = body.path("schemaName").isString() ? body.path("schemaName").asString() : null;
        SystemRow system = systems.findSystemByCode(systemCode)
                .orElseThrow(() -> ConfigApiException.validation("unknown system " + systemCode));

        ImportedSchema imported = importer.importDocument(format, body.path("document"), schemaName);

        String contractId = contracts.findContract(system.id(), role)
                .map(ContractSummary::id)
                .orElseGet(() -> contracts.insertContract(system.id(), contractCode(systemCode, role), role,
                        body.path("name").isString() && !body.path("name").asString().isBlank()
                                ? body.path("name").asString()
                                : systemCode + " " + role.toLowerCase()));
        List<VersionDetail> existing = contracts.listVersionDetails(contractId);
        if (existing.stream().anyMatch(version -> version.versionLabel().equals(label))) {
            throw ConfigApiException.conflict("the contract already has version " + label);
        }
        String derivedFrom = existing.stream()
                .filter(version -> compareLabels(version.versionLabel(), label) < 0)
                .max(Comparator.comparing(VersionDetail::versionLabel, ContractAdminService::compareLabels))
                .map(VersionDetail::id)
                .orElse(null);
        String versionId = contracts.insertImportedVersion(contractId, label, imported.jsonSchema().toString(),
                imported.openapiDocument() == null ? null : imported.openapiDocument().toString(), derivedFrom,
                actorId.toString());
        return contracts.findVersionDetail(versionId)
                .orElseThrow(() -> new IllegalStateException("the imported contract version vanished after insert"));
    }

    /** GET /api/v1/admin/contracts - every contract of the tenant with its versions side by side (RETIRED kept). */
    @Transactional(readOnly = true)
    public List<ContractWithVersions> listContracts() {
        Map<String, List<VersionDetail>> versions = contracts.listVersionDetails(null).stream()
                .collect(Collectors.groupingBy(VersionDetail::contractId));
        return contracts.listContracts().stream()
                .map(contract -> new ContractWithVersions(contract,
                        inLabelOrder(versions.getOrDefault(contract.id(), List.of()))))
                .toList();
    }

    /** GET /api/v1/admin/contracts/{contractId}/versions - 404 when the bound tenant cannot see the contract. */
    @Transactional(readOnly = true)
    public ContractWithVersions versionsOf(String contractId) {
        ContractSummary contract = requireUuid(contractId)
                .flatMap(contracts::findContractById)
                .orElseThrow(ConfigApiException::notFound);
        return new ContractWithVersions(contract, inLabelOrder(contracts.listVersionDetails(contract.id())));
    }

    /** GET /api/v1/admin/contracts/versions/{id} - any status, RETIRED included (ADR-011 Decision 3 rule 4). */
    @Transactional(readOnly = true)
    public VersionDetail readDetail(String versionId) {
        return requireUuid(versionId).flatMap(contracts::findVersionDetail).orElseThrow(ConfigApiException::notFound);
    }

    /**
     * POST /api/v1/admin/contracts/versions/{id}/approve - PENDING_APPROVAL to APPROVED by someone other than the
     * author (403 AUTHOR_IS_APPROVER); any other state is 409 NOT_PENDING_APPROVAL; a row the tenant cannot see 404.
     */
    @Transactional
    public VersionDetail approve(String versionId, UUID approverId) {
        String id = requireUuid(versionId).orElseThrow(ConfigApiException::notFound);
        String result = contracts.approveVersion(id, approverId.toString());
        return switch (result) {
            case "APPROVED" -> contracts.findVersionDetail(id)
                    .orElseThrow(() -> new IllegalStateException("the approved contract version vanished"));
            case "VERSION_NOT_FOUND" -> throw ConfigApiException.notFound();
            case "REFUSED_AUTHOR_IS_APPROVER" -> throw ConfigApiException.refusal(403, "AUTHOR_IS_APPROVER");
            default -> throw ConfigApiException.refusal(409, "NOT_PENDING_APPROVAL");
        };
    }

    /** The newest APPROVED version of the system's contract in {@code role} (T-106; RETIRED is no new pin). */
    @Transactional(readOnly = true)
    public Optional<VersionDetail> newestApproved(String systemCode, String role) {
        return systems.findSystemByCode(systemCode)
                .flatMap(system -> contracts.findContract(system.id(), role))
                .flatMap(contract -> contracts.listVersionDetails(contract.id()).stream()
                        .filter(version -> version.status().equals("APPROVED"))
                        .max(Comparator.comparing(VersionDetail::versionLabel, ContractAdminService::compareLabels)));
    }

    /** The source request contract keeps the E3 code (the system code); every other role is {@code code:ROLE}. */
    private static String contractCode(String systemCode, String role) {
        return role.equals("SOURCE_REQUEST") ? systemCode : systemCode + ":" + role;
    }

    private static Format format(String value) {
        for (Format format : Format.values()) {
            if (format.name().equals(value)) {
                return format;
            }
        }
        throw ConfigApiException.validation("format must be OPENAPI or JSON_SCHEMA");
    }

    private static List<VersionDetail> inLabelOrder(List<VersionDetail> versions) {
        return versions.stream()
                .sorted(Comparator.comparing(VersionDetail::versionLabel, ContractAdminService::compareLabels))
                .toList();
    }

    /** A path id that is not a uuid names no row of any tenant: 404, never a cast error. */
    private static Optional<String> requireUuid(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /** POST /api/v1/admin/contracts/versions - the draft-save (answers 200, it stores a row, it creates no new API resource). */
    @Transactional
    public VersionRow saveDraft(JsonNode body, UUID actorId) {
        String systemCode = SystemAdminService.required(body, "system");
        String role = SystemAdminService.required(body, "role");
        if (!role.equals("SOURCE_REQUEST")) {
            throw ConfigApiException.validation("role must be SOURCE_REQUEST in this cut");
        }
        String name = SystemAdminService.required(body, "name");
        JsonNode sourceSchema = body.path("sourceSchema");
        if (sourceSchema.isMissingNode() || sourceSchema.isNull()) {
            throw ConfigApiException.validation(
                    "sourceSchema is required: the draft-save stores it as the draft source contract version");
        }
        if (!sourceSchema.isObject()) {
            throw ConfigApiException.validation("sourceSchema must be a JSON Schema object");
        }
        SystemRow system = systems.findSystemByCode(systemCode)
                .orElseThrow(() -> ConfigApiException.validation("unknown system " + systemCode));
        String contractId = contracts.findContractByCode(systemCode)
                .map(ContractRow::id)
                .orElseGet(() -> contracts.insertContract(system.id(), systemCode, name));
        String label = nextLabel(contracts.listVersionLabels(contractId));
        String versionId = contracts.insertVersion(contractId, label, sourceSchema.toString(), actorId.toString());
        return contracts.findVersionById(versionId)
                .orElseThrow(() -> new IllegalStateException("the contract version row vanished after insert"));
    }

    /** POST /api/v1/admin/contracts/versions/{id}/submit - the definer move draft -> pending approval. */
    @Transactional
    public void submit(String versionId, JsonNode body) {
        String changeNote = SystemAdminService.required(body, "changeNote");
        String result = contracts.submitVersion(versionId, changeNote);
        switch (result) {
            case "SUBMITTED" -> {
                return;
            }
            case "VERSION_NOT_FOUND" -> throw ConfigApiException.notFound();
            default -> throw ConfigApiException.conflict(
                    "only a DRAFT contract version submits (the version answered " + result + ")");
        }
    }

    /**
     * The flow creation's source contract version (V2_2_4's NOT NULL composite): the newest draft version of the
     * source system's contract, created beside a skeleton contract and an empty-schema 1.0 draft when the system
     * has never been onboarded as a source. Runs inside the caller's flow-creation transaction.
     */
    public String getOrCreateSourceDraftVersion(String systemCode, UUID actorId) {
        SystemRow system = systems.findSystemByCode(systemCode)
                .orElseThrow(() -> ConfigApiException.validation("unknown source system " + systemCode));
        ContractRow contract = contracts.findContractByCode(systemCode)
                .orElseGet(() -> {
                    String id = contracts.insertContract(system.id(), systemCode, system.name());
                    return new ContractRow(id, system.id(), systemCode, system.name());
                });
        List<VersionRow> versions = contracts.listVersionsOfContract(contract.id());
        if (!versions.isEmpty()) {
            return latestByLabel(versions).id();
        }
        String versionId = contracts.insertVersion(contract.id(), "1.0",
                JSON.createObjectNode().toString(), actorId.toString());
        return contracts.findVersionById(versionId)
                .orElseThrow(() -> new IllegalStateException("the skeleton contract version vanished after insert"))
                .id();
    }

    /** The next version label: {@code 1.0} for the first, minor + 1 over the highest existing. */
    private static String nextLabel(List<String> labels) {
        int next = 0;
        for (String label : labels) {
            int dot = label.indexOf('.');
            if (dot > 0 && label.substring(dot + 1).chars().allMatch(Character::isDigit)) {
                next = Math.max(next, Integer.parseInt(label.substring(dot + 1)) + 1);
            }
        }
        return "1." + next;
    }

    private static VersionRow latestByLabel(List<VersionRow> versions) {
        VersionRow best = versions.get(0);
        for (VersionRow candidate : versions) {
            if (compareLabels(candidate.versionLabel(), best.versionLabel()) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static int compareLabels(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < 2; i++) {
            int difference = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    /** The draft-save's answer keeps the schema as a JSON tree, not as a quoted string. */
    public static JsonNode sourceSchemaNode(String jsonSchemaText) {
        return JSON.readTree(jsonSchemaText);
    }

    /** Optional view used by the flow detail's contract reference; the API never exposes it empty. */
    public Optional<VersionRow> findVersion(String versionId) {
        return contracts.findVersionById(versionId);
    }
}
