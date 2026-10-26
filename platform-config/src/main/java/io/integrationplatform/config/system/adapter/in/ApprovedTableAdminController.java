package io.integrationplatform.config.system.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.system.application.ApprovedTableService;
import io.integrationplatform.tenancy.port.LoginBinding;
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
 * E16 F3: the approved tables of a DB system. A configurator requests a table, a different approver approves or
 * revokes it. HTTP-only seam - the rules are {@link ApprovedTableService}'s; the role of each endpoint is in
 * {@link SystemEndpointRoles} (request: EDIT_CONFIGURATION; approve and revoke: APPROVE_DB_TABLE). The tenant and the
 * acting user come from the validated token only, never the body; every call leaves a log line
 * ({@link ApprovedTableAudit}) and every completed one an audit event.
 */
@RestController
@RequestMapping(ApprovedTableAdminController.PATH)
public class ApprovedTableAdminController {

    static final String PATH = "/api/v1/admin/systems/{systemId}/approved-tables";

    private final ApprovedTableService service;
    private final ConfigActors actors;

    public ApprovedTableAdminController(ApprovedTableService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String systemId) {
        actors.requireActor();
        return ResponseEntity.ok(service.list(systemId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(@PathVariable String systemId, @RequestBody JsonNode body) {
        LoginBinding actor = actors.requireActor();
        JsonNode table = body == null ? null : body.get("table");
        String subject = table != null && table.isString() ? table.stringValue() : "-";
        try {
            Map<String, Object> created = service.request(systemId, body, actor.userId());
            ApprovedTableAudit.record("db.table.request", actor, systemId, subject, "REQUESTED");
            return ResponseEntity.status(201).body(created);
        } catch (ConfigApiException refused) {
            ApprovedTableAudit.record("db.table.request", actor, systemId, subject,
                    ApprovedTableAudit.outcomeOf(refused));
            throw refused;
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String systemId, @PathVariable String id) {
        return decide("db.table.approve", systemId, id, "APPROVE", "APPROVED");
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String systemId, @PathVariable String id) {
        return decide("db.table.revoke", systemId, id, "REVOKE", "REVOKED");
    }

    private ResponseEntity<Map<String, Object>> decide(String event, String systemId, String id, String decision,
            String done) {
        LoginBinding actor = actors.requireActor();
        try {
            Map<String, Object> decided = service.decide(systemId, id, decision, actor.userId());
            ApprovedTableAudit.record(event, actor, systemId, id, done);
            return ResponseEntity.ok(decided);
        } catch (ConfigApiException refused) {
            ApprovedTableAudit.record(event, actor, systemId, id, ApprovedTableAudit.outcomeOf(refused));
            throw refused;
        }
    }
}
