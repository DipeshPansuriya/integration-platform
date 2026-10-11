package io.integrationplatform.config.credential.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.credential.application.SourceCredentialService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * E5 T-103: the source-credential admin API (ADR-007 Decision 3) - issue, list, rotate and revoke the machine
 * credentials of a source system. HTTP-only seam: the rules are {@link SourceCredentialService}'s, the role of each
 * endpoint is in {@link CredentialEndpointRoles}. Handler names are unique across the API so no existing OpenAPI
 * operationId shifts.
 *
 * <p>An issued or rotated credential answers {@code {"id","systemId","customerId","clientId","status","secret"}} - the
 * secret once, here only; a listed one answers its metadata with {@code "secret": "********"}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class SourceCredentialAdminController {

    private final SourceCredentialService service;
    private final ConfigActors actors;

    public SourceCredentialAdminController(SourceCredentialService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Issued: a confidential client exists; the secret is shown"
                + " this once and never again"),
        @ApiResponse(responseCode = "400", description = "customerId is missing or not a text",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the system is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "The system is not a source system",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "503", description = "The identity provider did not create the client (or no admin"
                + " client is configured); nothing was created - try again later",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/systems/{systemId}/source-credentials")
    public ResponseEntity<Map<String, Object>> issueSourceCredential(@PathVariable String systemId,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(201).body(service.issue(systemId, body, actors.requireActor()));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The source system's credentials, oldest first, metadata only"
                + " (the secret is a masked marker)"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the system is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "The system is not a source system",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "503", description = "Source credential administration is not available here",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/systems/{systemId}/source-credentials")
    public ResponseEntity<List<Map<String, Object>>> listSourceCredentials(@PathVariable String systemId) {
        actors.requireActor();
        return ResponseEntity.ok(service.list(systemId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "The replacement is issued (its secret shown this once); the"
                + " replaced credential keeps working until replacedRevokeAfter (the grace window)"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the credential is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "The credential is not ACTIVE, or is already being rotated",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "503", description = "The identity provider did not create the replacement; the"
                + " old credential is unchanged - try again later",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/source-credentials/{credentialId}/rotate")
    public ResponseEntity<Map<String, Object>> rotateSourceCredential(@PathVariable String credentialId) {
        return ResponseEntity.status(201).body(service.rotate(credentialId, actors.requireActor()));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Revoked on the platform at once; idpDisablePending tells"
                + " whether the identity provider's client disable is still waiting for the reconciliation"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow the operation",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the credential is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "The credential is already revoked",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "503", description = "Source credential administration is not available here",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/source-credentials/{credentialId}/revoke")
    public ResponseEntity<Map<String, Object>> revokeSourceCredential(@PathVariable String credentialId) {
        return ResponseEntity.ok(service.revoke(credentialId, actors.requireActor()));
    }
}
