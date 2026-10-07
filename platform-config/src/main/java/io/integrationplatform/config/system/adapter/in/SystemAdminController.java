package io.integrationplatform.config.system.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemRow;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The systems admin API (T-015's onboarding premise): register a system; an OUTBOUND registration carries its
 * default target endpoint so a flow can name it as a destination. HTTP-only seam - the rules are the service's.
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
        @ApiResponse(responseCode = "400", description = "A validation refusal names the refused field or value",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER: the bearer resolves to no directory user",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Conflict: a system with this code is already registered",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body) {
        actors.requireActor();
        SystemRow created = service.create(body);
        return ResponseEntity.status(201).body(ConfigApiException.body(
                "id", created.id(), "code", created.code(), "name", created.name(),
                "direction", created.direction()));
    }
}
