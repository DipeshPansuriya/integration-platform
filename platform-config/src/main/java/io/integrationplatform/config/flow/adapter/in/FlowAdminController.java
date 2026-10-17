package io.integrationplatform.config.flow.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.flow.application.FlowAdminService;
import io.integrationplatform.config.flow.application.FlowRoutesQuery;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final FlowRoutesQuery routes;

    public FlowAdminController(FlowAdminService service, ConfigActors actors, FlowRoutesQuery routes) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created"),
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field or value",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a named destination clashes with a registered system code",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body) {
        InterfaceRow created = service.create(body, actors.requireActor().userId());
        return ResponseEntity.status(201).body(ConfigApiException.body("id", created.id()));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        actors.requireActor();
        return ResponseEntity.ok(service.list());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{flowId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String flowId) {
        actors.requireActor();
        return ResponseEntity.ok(service.read(flowId));
    }

    /**
     * T81 / B9: the routes the flow designer navigates by - the current (latest) version's bundle routes, or those of
     * the version named by {@code versionId}: id, name (the target endpoint's), sequence, isPrimary, status,
     * condition, targetEndpointId, targetEndpointCode, targetSystem and the mappingId the designer opens.
     */
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the flow, or the named version of it, is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{flowId}/routes")
    public ResponseEntity<Map<String, Object>> routes(@PathVariable String flowId,
            @RequestParam(required = false) String versionId) {
        actors.requireActor();
        return ResponseEntity.ok(routes.routes(flowId, versionId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field or value",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a named destination clashes with a registered system code",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping("/{flowId}")
    public ResponseEntity<Map<String, Object>> saveDraft(@PathVariable String flowId, @RequestBody JsonNode body) {
        return ResponseEntity.ok(service.saveDraft(flowId, body, actors.requireActor().userId()));
    }

    /** The T-019 refusal seam: a version's content is never edited in place; the attempt answers 409. */
    @ApiResponses({
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: version content is immutable - save a new draft on the flow",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping("/{flowId}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> editVersion(@PathVariable String flowId,
            @PathVariable String versionId) {
        actors.requireActor();
        service.refuseVersionEdit(flowId, versionId);
        throw new IllegalStateException("the version edit is always refused - this line is unreachable");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field or value",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: the flow has no draft version to submit",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{flowId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String flowId, @RequestBody JsonNode body) {
        return ResponseEntity.ok(service.submit(flowId, body, actors.requireActor().userId()));
    }

    /** The approval answer is designed, not thrown: a refusal commits (the audit row the database recorded). */
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or AUTHOR_IS_APPROVER: the author never approves their own version",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: NOT_PENDING_APPROVAL, or no version is pending approval",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{flowId}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String flowId) {
        FlowAdminService.ApprovalOutcome outcome = service.approve(flowId, actors.requireActor().userId());
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: the flow has no approved version to publish",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{flowId}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String flowId) {
        return ResponseEntity.ok(service.publish(flowId, actors.requireActor().userId()));
    }
}
