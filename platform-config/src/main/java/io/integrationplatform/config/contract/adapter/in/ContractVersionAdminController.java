package io.integrationplatform.config.contract.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionRow;
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
 */
@RestController
@RequestMapping(ContractVersionAdminController.PATH)
public class ContractVersionAdminController {

    static final String PATH = "/api/v1/admin/contracts/versions";

    private final ContractAdminService service;
    private final ConfigActors actors;

    public ContractVersionAdminController(ContractAdminService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveDraft(@RequestBody JsonNode body) {
        VersionRow saved = service.saveDraft(body, actors.requireActor().userId());
        return ResponseEntity.ok(ConfigApiException.body(
                "id", saved.id(), "versionLabel", saved.versionLabel(), "status", saved.status()));
    }

    @GetMapping("/{versionId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String versionId) {
        actors.requireActor();
        VersionRow row = service.read(versionId);
        return ResponseEntity.ok(ConfigApiException.body(
                "id", row.id(),
                "system", row.systemCode(),
                "role", "SOURCE_REQUEST",
                "versionLabel", row.versionLabel(),
                "status", row.status(),
                "sourceSchema", ContractAdminService.sourceSchemaNode(row.jsonSchema()),
                "changeNote", row.changeNote()));
    }

    @PostMapping("/{versionId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String versionId, @RequestBody JsonNode body) {
        actors.requireActor();
        service.submit(versionId, body);
        return ResponseEntity.ok(ConfigApiException.body("id", versionId));
    }
}
