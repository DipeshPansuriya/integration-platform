package io.integrationplatform.config.system.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.integrationplatform.tenancy.port.LoginBinding;
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
 * The systems admin API: register (T-015, extended by E5 T-102 with type + settings), list, open and edit systems,
 * and test an API system's connection (T-104). HTTP-only seam - the rules are the service's; the role of each endpoint
 * is in {@link SystemEndpointRoles} (and ConfigEndpointRoles for the E3 POST).
 *
 * <p>A system answers as {@code {"id","code","name","direction","type","settings"}}; {@code type} and {@code settings}
 * are JSON null for an E3 untyped system.
 */
@RestController
@RequestMapping(SystemAdminController.PATH)
public class SystemAdminController {

    static final String PATH = "/api/v1/admin/systems";

    private final SystemAdminService service;
    private final ConfigActors actors;

    public SystemAdminController(SystemAdminService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created"),
        @ApiResponse(responseCode = "400", description = "Every validation error is listed (errors) and joined (error)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a system with this code is already registered",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body) {
        LoginBinding actor = actors.requireActor();
        return ResponseEntity.status(201).body(service.create(body, actor.userId()));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Every system of the bound tenant, ordered by code"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSystems() {
        actors.requireActor();
        return ResponseEntity.ok(service.list());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{systemId}")
    public ResponseEntity<Map<String, Object>> readSystem(@PathVariable String systemId) {
        actors.requireActor();
        return ResponseEntity.ok(service.read(systemId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK: the edited system"),
        @ApiResponse(responseCode = "400", description = "Every validation error is listed (errors) and joined (error)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping("/{systemId}")
    public ResponseEntity<Map<String, Object>> updateSystem(@PathVariable String systemId, @RequestBody JsonNode body) {
        LoginBinding actor = actors.requireActor();
        return ResponseEntity.ok(service.update(systemId, body, actor.userId()));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The endpoint's auth settings"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Not an outbound system, or no target endpoint",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{systemId}/endpoint-auth")
    public ResponseEntity<Map<String, Object>> readEndpointAuth(@PathVariable String systemId) {
        actors.requireActor();
        return ResponseEntity.ok(service.readEndpointAuth(systemId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved: the updated auth settings"),
        @ApiResponse(responseCode = "400", description = "Every validation error is listed (errors) and joined (error)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Not an outbound system, or no target endpoint",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping("/{systemId}/endpoint-auth")
    public ResponseEntity<Map<String, Object>> updateEndpointAuth(@PathVariable String systemId,
            @RequestBody JsonNode body) {
        actors.requireActor();
        return ResponseEntity.ok(service.updateEndpointAuth(systemId, body));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The result: SUCCESS (httpStatus, responseTimeMs), REFUSED"
                + " (reason, before any data is sent), FAILED (reason), NOT_AVAILABLE_YET (the type's test arrives with"
                + " its adapter) or NOT_CONFIGURED"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the row is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "429", description = "TEST_CONNECTION_RATE_LIMITED: over the per-user limit",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{systemId}/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable String systemId) {
        return ResponseEntity.ok(service.testConnection(systemId, actors.requireActor()));
    }
}
