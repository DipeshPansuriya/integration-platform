package io.integrationplatform.config.contract.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.ContractRow;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemRow;
import io.integrationplatform.config.system.application.SystemAdminService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 */
public class ContractAdminService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcContractRepository contracts;
    private final JdbcSystemRepository systems;

    public ContractAdminService(JdbcContractRepository contracts, JdbcSystemRepository systems) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.systems = Objects.requireNonNull(systems, "systems");
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

    /** GET /api/v1/admin/contracts/versions/{id}. */
    @Transactional(readOnly = true)
    public VersionRow read(String versionId) {
        return contracts.findVersionById(versionId).orElseThrow(ConfigApiException::notFound);
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
