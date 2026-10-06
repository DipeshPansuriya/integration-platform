package io.integrationplatform.config.flow.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.flow.application.FlowAdminService;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The flow admin and lifecycle API (T-015 create/read, T-019 draft-save, submit, approve, publish, T-021's atomic
 * publish endpoint). The tenant never travels in the path (ADR-011 Decision 4): it comes from the bearer, bound
 * by the security chain, and every row access is tenant-scoped - another tenant's flow answers 404, never 403.
 */
@RestController
@RequestMapping(FlowAdminController.PATH)
public class FlowAdminController {

    static final String PATH = "/api/v1/admin/flows";

    private final FlowAdminService service;
    private final ConfigActors actors;

    public FlowAdminController(FlowAdminService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body) {
        InterfaceRow created = service.create(body, actors.requireActor().userId());
        return ResponseEntity.status(201).body(ConfigApiException.body("id", created.id()));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        actors.requireActor();
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{flowId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String flowId) {
        actors.requireActor();
        return ResponseEntity.ok(service.read(flowId));
    }

    @PutMapping("/{flowId}")
    public ResponseEntity<Map<String, Object>> saveDraft(@PathVariable String flowId, @RequestBody JsonNode body) {
        return ResponseEntity.ok(service.saveDraft(flowId, body, actors.requireActor().userId()));
    }

    /** The T-019 refusal seam: a version's content is never edited in place; the attempt answers 409. */
    @PutMapping("/{flowId}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> editVersion(@PathVariable String flowId,
            @PathVariable String versionId) {
        actors.requireActor();
        service.refuseVersionEdit(flowId, versionId);
        throw new IllegalStateException("the version edit is always refused - this line is unreachable");
    }

    @PostMapping("/{flowId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String flowId, @RequestBody JsonNode body) {
        actors.requireActor();
        return ResponseEntity.ok(service.submit(flowId, body));
    }

    /** The approval answer is designed, not thrown: a refusal commits (the audit row the database recorded). */
    @PostMapping("/{flowId}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String flowId) {
        FlowAdminService.ApprovalOutcome outcome = service.approve(flowId, actors.requireActor().userId());
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @PostMapping("/{flowId}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String flowId) {
        actors.requireActor();
        return ResponseEntity.ok(service.publish(flowId));
    }
}
