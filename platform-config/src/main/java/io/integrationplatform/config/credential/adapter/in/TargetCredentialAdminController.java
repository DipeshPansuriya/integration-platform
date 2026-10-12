package io.integrationplatform.config.credential.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.credential.application.TargetCredentialService;
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
 * E5 T-103 / T-185: the target-credential admin API (ADR-007 Decision 7) - write-only secrets of a target endpoint,
 * one {@code target_credential} row per field of the endpoint's target type. HTTP-only seam: the rules are
 * {@link TargetCredentialService}'s, the role of each endpoint is in {@link CredentialEndpointRoles}.
 *
 * <p>Both answers are {@code {"targetEndpointId","targetType","fields":[{"field","credentialId","value":"********",
 * "updatedAt"}],"allowedFields":[...]}} - a value is never returned.
 */
@RestController
@RequestMapping("/api/v1/admin/target-endpoints/{endpointId}/credentials")
public class TargetCredentialAdminController {

    private final TargetCredentialService service;
    private final ConfigActors actors;

    public TargetCredentialAdminController(TargetCredentialService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The fields that are set, each masked"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the endpoint is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<Map<String, Object>> readTargetCredentials(@PathVariable String endpointId) {
        actors.requireActor();
        return ResponseEntity.ok(service.read(endpointId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved: each field sealed in its own row; the masked view"),
        @ApiResponse(responseCode = "400", description = "Every validation error is listed (errors) and joined (error)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the endpoint is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "The target system has no type yet",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "503", description = "No active key-encryption key on this instance",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping
    public ResponseEntity<Map<String, Object>> saveTargetCredentials(@PathVariable String endpointId,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.ok(service.save(endpointId, body, actors.requireActor()));
    }
}
