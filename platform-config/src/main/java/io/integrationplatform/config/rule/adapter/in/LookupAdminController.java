package io.integrationplatform.config.rule.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.rule.application.LookupEntryService;
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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The lookup-entry end of the rules API (T-163, E7-F3-S1): the entries of one lookup table
 * ({@code GET /api/v1/admin/lookups/{lookupId}/entries}) and the in-place change
 * ({@code PUT /api/v1/admin/lookups/{lookupId}/entries/{entryId}}, {@code {key, value}}) that the next evaluation
 * sees with no publish and no restart, audited by {@link LookupEntryService}. The lookup id is its LOOKUP rule
 * set's id; another tenant's (or unknown, or a non-lookup) id answers 404. Role rules: {@link LookupEndpointRoles};
 * refusals leave through the config error seam.
 */
@RestController
public class LookupAdminController {

    static final String LOOKUP_ENTRIES = "/api/v1/admin/lookups/{lookupId}/entries";
    static final String LOOKUP_ENTRY = LOOKUP_ENTRIES + "/{entryId}";

    private final LookupEntryService service;
    private final ConfigActors actors;

    public LookupAdminController(LookupEntryService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "OK: {lookupId, entries: [{id, key, value}]} in table order"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow reading",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the lookup is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping(LOOKUP_ENTRIES)
    public ResponseEntity<Map<String, Object>> listEntries(@PathVariable String lookupId) {
        actors.requireActor();
        return ResponseEntity.ok(service.list(lookupId));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK: {id, key, value} as changed, effective immediately"),
        @ApiResponse(responseCode = "400", description = "RULE_TABLE_INVALID with the cell and the reason, or a"
                + " validation refusal (key, value, the table's column shape)",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or the role does not allow editing",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404",
                description = "Not found: the lookup or the entry is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PutMapping(LOOKUP_ENTRY)
    public ResponseEntity<Map<String, Object>> changeEntry(@PathVariable String lookupId,
            @PathVariable String entryId, @RequestBody JsonNode body) {
        return ResponseEntity.ok(service.change(lookupId, entryId, body, actors.requireActor()));
    }
}