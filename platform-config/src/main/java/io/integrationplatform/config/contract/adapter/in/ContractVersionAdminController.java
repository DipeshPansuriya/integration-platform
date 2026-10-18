package io.integrationplatform.config.contract.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.contract.application.ContractAdminService.ContractWithVersions;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionDetail;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionRow;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The contract-version admin API (the T-092/T-093 nodes the lifecycle keeps, ADR-011 Decision 2): the draft-save
 * stores {@code sourceSchema} as a new draft source contract version (200 - it stores a row of an existing
 * contract, it creates no new resource), the version is readable, and the submit stores the change note as the
 * version walks to pending approval.
 *
 * <p>E5 lane A (T-105): the import of a source or target contract version from OpenAPI or JSON Schema (201), the
 * listing of every contract with its versions side by side, one contract's versions, and the non-author approval.
 * All on this one controller (the matrix coverage guard reads this class); the class mapping moved up one segment to
 * {@code /api/v1/admin/contracts} and every E3 pattern is registered exactly as before. Each new endpoint's role
 * operation is in {@link ContractEndpointRoles}; each approve and import leaves one audit event
 * ({@link ContractAudit}).
 */
@RestController
@RequestMapping(ContractVersionAdminController.CONTRACTS)
public class ContractVersionAdminController {

    static final String CONTRACTS = "/api/v1/admin/contracts";
    static final String PATH = CONTRACTS + "/versions";

    private static final String JSON = "application/json";

    private final ContractAdminService service;
    private final ConfigActors actors;

    public ContractVersionAdminController(ContractAdminService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field or value",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a concurrent save took the same contract or version label",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/versions")
    public ResponseEntity<Map<String, Object>> saveDraft(@RequestBody JsonNode body) {
        VersionRow saved = service.saveDraft(body, actors.requireActor().userId());
        return ResponseEntity.ok(ConfigApiException.body(
                "id", saved.id(), "versionLabel", saved.versionLabel(), "status", saved.status()));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The version with its schema, any status (RETIRED stays readable)",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ContractApiBodies.ContractVersionBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String versionId) {
        actors.requireActor();
        return ResponseEntity.ok(versionBody(service.readDetail(versionId)));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field or value",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: only a DRAFT contract version submits",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/versions/{versionId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String versionId, @RequestBody JsonNode body) {
        service.submit(versionId, body, actors.requireActor().userId());
        return ResponseEntity.ok(ConfigApiException.body("id", versionId));
    }

    // ------------------------------------------------------------------ E5 lane A (T-105)

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "The new DRAFT version, its schema imported",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ContractApiBodies.ContractVersionBody.class))),
        @ApiResponse(responseCode = "400", description = "A refused field, or a document that does not parse - with the parser's errors",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ContractApiBodies.ContractImportErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: editing configuration needs TENANT_ADMIN or CONFIGURATOR",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: the contract already has this version label",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/versions/import")
    public ResponseEntity<Map<String, Object>> importVersion(@RequestBody JsonNode body) {
        LoginBinding actor = actors.requireActor();
        try {
            VersionDetail imported = service.importVersion(body, actor.userId());
            ContractAudit.record("contract.version.import", actor, imported.id(), "IMPORTED");
            return ResponseEntity.status(201).body(versionBody(imported));
        } catch (ConfigApiException refused) {
            ContractAudit.record("contract.version.import", actor, "-", ContractAudit.outcomeOf(refused));
            throw refused;
        }
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Every contract of the tenant with its versions side by side",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ContractApiBodies.ContractListBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: reading configuration needs a configuration role",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<Map<String, Object>> listContracts() {
        actors.requireActor();
        List<Map<String, Object>> contracts = new ArrayList<>();
        service.listContracts().forEach(contract -> contracts.add(contractBody(contract)));
        return ResponseEntity.ok(ConfigApiException.body("contracts", contracts));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "One contract with its versions side by side",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ContractApiBodies.ContractBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: reading configuration needs a configuration role",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the contract is not visible to the bound tenant",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{contractId}/versions")
    public ResponseEntity<Map<String, Object>> versionsOf(@PathVariable String contractId) {
        actors.requireActor();
        return ResponseEntity.ok(contractBody(service.versionsOf(contractId)));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The version, now APPROVED by the caller",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ContractApiBodies.ContractVersionBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, ROLE_NOT_GRANTED, or AUTHOR_IS_APPROVER: the author never approves their own version",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the version is not visible to the bound tenant",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "NOT_PENDING_APPROVAL: only a submitted version is approvable",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/versions/{versionId}/approve")
    public ResponseEntity<Map<String, Object>> approveContractVersion(@PathVariable String versionId) {
        LoginBinding actor = actors.requireActor();
        try {
            VersionDetail approved = service.approve(versionId, actor.userId());
            ContractAudit.record("contract.version.approve", actor, approved.id(), "APPROVED");
            return ResponseEntity.ok(versionBody(approved));
        } catch (ConfigApiException refused) {
            ContractAudit.record("contract.version.approve", actor, versionId, ContractAudit.outcomeOf(refused));
            throw refused;
        }
    }

    // ------------------------------------------------------------------ bodies

    /** The full version: the E3 fields (sourceSchema kept for the draft screens) plus the T-105 ones. */
    private static Map<String, Object> versionBody(VersionDetail version) {
        JsonNode schema = ContractAdminService.sourceSchemaNode(version.jsonSchema());
        return ConfigApiException.body(
                "id", version.id(),
                "contractId", version.contractId(),
                "system", version.systemCode(),
                "role", version.role(),
                "versionLabel", version.versionLabel(),
                "status", version.status(),
                "jsonSchema", schema,
                "sourceSchema", schema,
                "openapiDocument", version.openapiDocument() == null
                        ? null : ContractAdminService.sourceSchemaNode(version.openapiDocument()),
                "derivedFromVersionId", version.derivedFromVersionId(),
                "changeNote", version.changeNote(),
                "authorId", version.authorId(),
                "approverId", version.approverId(),
                "createdAt", version.createdAt().toString());
    }

    private static Map<String, Object> contractBody(ContractWithVersions listed) {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (VersionDetail version : listed.versions()) {
            versions.add(ConfigApiException.body(
                    "id", version.id(),
                    "versionLabel", version.versionLabel(),
                    "status", version.status(),
                    "derivedFromVersionId", version.derivedFromVersionId(),
                    "authorId", version.authorId(),
                    "approverId", version.approverId(),
                    "createdAt", version.createdAt().toString()));
        }
        return ConfigApiException.body(
                "id", listed.contract().id(),
                "contractId", listed.contract().id(),
                "system", listed.contract().systemCode(),
                "role", listed.contract().role(),
                "code", listed.contract().code(),
                "name", listed.contract().name(),
                "versions", versions);
    }
}
