package io.integrationplatform.config.rule.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.rule.application.RuleSetAdminService;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.util.RawValue;

/**
 * The rule-set admin API (T-023, E6-F1-S1) - the cross-lane contract lanes C and D code against:
 * {@code POST/GET /api/v1/admin/flows/{flowId}/rule-sets}, {@code GET/PUT /api/v1/admin/rule-sets/{ruleSetId}}. The
 * answer is {@code {id, flowId, kind, name, content}} where {@code content} is written raw: byte for byte the JSON that
 * was saved, so the editor re-opens a table exactly as it was left. The tenant comes from the bearer only; another
 * tenant's id answers 404. Role rules: {@link RuleEndpointRoles}; refusals leave through the config error seam.
 */
@RestController
public class RuleSetAdminController {

    static final String FLOW_RULE_SETS = "/api/v1/admin/flows/{flowId}/rule-sets";
    static final String RULE_SET = "/api/v1/admin/rule-sets/{ruleSetId}";

    private final RuleSetAdminService service;
    private final ConfigActors actors;

    public RuleSetAdminController(RuleSetAdminService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created: {id, flowId, kind, name, content}"),
        @ApiResponse(responseCode = "400", description = "RULE_TABLE_INVALID with the cell and the reason, or a"
                + " validation refusal (kind, name)",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = RuleTableInvalidBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow editing",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the flow is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a lookup table of that name exists",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping(FLOW_RULE_SETS)
    public ResponseEntity<Map<String, Object>> create(@PathVariable String flowId, @RequestBody JsonNode body) {
        RuleSetRow created = service.create(flowId, body, actors.requireActor().userId());
        return ResponseEntity.status(201).body(view(created));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK: the flow's rule sets in authoring order"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow reading",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the flow is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping(FLOW_RULE_SETS)
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String flowId) {
        actors.requireActor();
        return ResponseEntity.ok(service.list(flowId).stream().map(RuleSetAdminController::view).toList());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK: {id, flowId, kind, name, content}"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow reading",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the rule set is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping(RULE_SET)
    public ResponseEntity<Map<String, Object>> read(@PathVariable String ruleSetId) {
        actors.requireActor();
        return ResponseEntity.ok(view(service.read(ruleSetId)));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK: {id, flowId, kind, name, content}"),
        @ApiResponse(responseCode = "400", description = "RULE_TABLE_INVALID with the cell and the reason, or a"
                + " validation refusal (name)",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = RuleTableInvalidBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow editing",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the rule set is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a lookup table of that name exists",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping(RULE_SET)
    public ResponseEntity<Map<String, Object>> update(@PathVariable String ruleSetId, @RequestBody JsonNode body) {
        return ResponseEntity.ok(view(service.update(ruleSetId, body, actors.requireActor().userId())));
    }

    private static Map<String, Object> view(RuleSetRow row) {
        return ConfigApiException.body("id", row.id(), "flowId", row.flowId(), "kind", row.kind(),
                "name", row.name(), "content", new RawValue(row.content()));
    }
}
