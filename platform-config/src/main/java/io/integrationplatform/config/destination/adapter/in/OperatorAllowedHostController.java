package io.integrationplatform.config.destination.adapter.in;

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
 * Operator-scoped management of the destination allow-list: the patterns stored in
 * {@code operator_allowed_host} (V2_4_1410). These entries are unioned with the deployment
 * configuration ({@code platform.config.destinations.allowed-hosts}) by {@link DbBackedAllowList},
 * so changes take effect immediately without a restart.
 *
 * <p>Row 578 (CWE-862): the list is ONE global list bounding every tenant's outbound calls, so each route is an
 * OPERATOR_AGENT operation ({@link OperatorAllowedHostEndpointRoles}); the role interceptor refuses every tenant
 * role with 403 before a handler runs, and the operator agent binds no tenant user, so no actor is read here.
 */
@RestController
@RequestMapping(OperatorAllowedHostController.PATH)
public class OperatorAllowedHostController {

    static final String PATH = "/api/v1/admin/operator/allowed-hosts";

    private final DbBackedAllowList allowList;

    public OperatorAllowedHostController(DbBackedAllowList allowList) {
        this.allowList = Objects.requireNonNull(allowList, "allowList");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "All DB-stored allowed-host patterns"),
        @ApiResponse(responseCode = "403", description = "ROLE_NOT_GRANTED (every tenant role; operator agent only)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(allowList.list().stream()
                .map(e -> Map.<String, Object>of("id", e.id().toString(), "pattern", e.pattern()))
                .toList());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pattern added"),
        @ApiResponse(responseCode = "400", description = "Invalid or duplicate pattern",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "ROLE_NOT_GRANTED (every tenant role; operator agent only)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        String pattern = body.getOrDefault("pattern", "").trim();
        if (pattern.isEmpty()) {
            throw ConfigApiException.validation("pattern is required");
        }
        try {
            HostEntry entry = allowList.add(pattern);
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
        @ApiResponse(responseCode = "403", description = "ROLE_NOT_GRANTED (every tenant role; operator agent only)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Pattern not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        if (!allowList.remove(id)) {
            throw ConfigApiException.notFound();
        }
        return ResponseEntity.noContent().build();
    }
}
