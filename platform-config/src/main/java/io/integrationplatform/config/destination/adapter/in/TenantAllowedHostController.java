package io.integrationplatform.config.destination.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.destination.DbBackedAllowList;
import io.integrationplatform.config.destination.DbBackedAllowList.HostEntry;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped view of the destination allow-list (Dipesh, 2026-10-29): the same DB-backed patterns
 * as the operator controller, but gated by READ_DESTINATIONS / EDIT_DESTINATIONS (TENANT_ADMIN only).
 * The operator endpoints ({@link OperatorAllowedHostController}) remain unchanged.
 */
@RestController
@RequestMapping(TenantAllowedHostController.PATH)
public class TenantAllowedHostController {

    static final String PATH = "/api/v1/admin/destinations";

    private final DbBackedAllowList allowList;
    private final ConfigActors actors;

    public TenantAllowedHostController(DbBackedAllowList allowList, ConfigActors actors) {
        this.allowList = Objects.requireNonNull(allowList, "allowList");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "All DB-stored allowed-host patterns"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER or ROLE_NOT_GRANTED",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        actors.requireActor();
        return ResponseEntity.ok(allowList.list().stream()
                .map(e -> Map.<String, Object>of("id", e.id().toString(), "pattern", e.pattern()))
                .toList());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pattern added"),
        @ApiResponse(responseCode = "400", description = "Invalid or duplicate pattern",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER or ROLE_NOT_GRANTED",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        String actor = actors.requireActor().userId().toString();
        String pattern = body.getOrDefault("pattern", "").trim();
        if (pattern.isEmpty()) {
            throw ConfigApiException.validation("pattern is required");
        }
        try {
            HostEntry entry = allowList.add(pattern, actor);
            return ResponseEntity.status(201)
                    .body(Map.of("id", entry.id().toString(), "pattern", entry.pattern()));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw ConfigApiException.conflict("pattern already exists: " + pattern);
        } catch (IllegalArgumentException invalid) {
            throw ConfigApiException.validation(invalid.getMessage());
        }
    }

    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Pattern removed"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER or ROLE_NOT_GRANTED",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Pattern not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        String actor = actors.requireActor().userId().toString();
        if (!allowList.remove(id, actor)) {
            throw ConfigApiException.notFound();
        }
        return ResponseEntity.noContent().build();
    }
}
