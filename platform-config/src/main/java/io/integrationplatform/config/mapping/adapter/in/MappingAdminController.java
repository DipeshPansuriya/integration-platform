package io.integrationplatform.config.mapping.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.mapping.application.MappingAdminService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The mapping save and read API (T-190): {@code GET/PUT /api/v1/admin/routes/{routeId}/mapping}. The tenant
 * never travels in the path (ADR-011 Decision 4); the roles are read- and edit-configuration (MappingEndpointRoles),
 * and another tenant's route answers 404, never 403.
 */
@RestController
@RequestMapping("/api/v1/admin/routes")
public class MappingAdminController {

    private final MappingAdminService mappings;
    private final ConfigActors actors;

    public MappingAdminController(MappingAdminService mappings, ConfigActors actors) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The mapping as it was saved"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the route is not visible to the bound tenant",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{routeId}/mapping")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String routeId) {
        actors.requireActor();
        return ResponseEntity.ok(mappings.read(routeId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved; the mapping_version id when bound"),
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field, link or binding",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the route is not visible to the bound tenant",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a binding that is not an approved contract"
                + " of its role, or a version-number contention under concurrent saves",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping("/{routeId}/mapping")
    public ResponseEntity<Map<String, Object>> save(@PathVariable String routeId, @RequestBody JsonNode body) {
        return ResponseEntity.ok(mappings.save(routeId, body, actors.requireActor().userId()));
    }
}