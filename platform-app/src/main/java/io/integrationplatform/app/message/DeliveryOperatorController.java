package io.integrationplatform.app.message;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions.Result;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E13 N2 (T111, T-166 narrow): the operator retry ({@code RETRY_WAIT -> DUE} now) and replay
 * ({@code DEAD_LETTERED -> DUE}) of one delivery, sent again with its pinned mapping and contract versions, beside
 * the T110 tracking reads. The actor is the bound user; the tenant comes from the bearer, never the path. A delivery
 * the bound tenant cannot see is 404 plus one {@code PATH_ID} denied-attempt record (the T110 contract); the wrong
 * state or a missing pin is 409 and changes nothing. Rules: {@link DeliveryOperatorEndpointRoles}.
 */
@RestController
@RequestMapping(DeliveryOperatorEndpointRoles.DELIVERIES)
public class DeliveryOperatorController {

    private final DeliveryOperatorActions actions;
    private final ConfigActors actors;
    private final ObjectProvider<TenantDenialRecorder> recorders;
    private final ObjectProvider<TenantContext> tenantContexts;

    public DeliveryOperatorController(DeliveryOperatorActions actions, ConfigActors actors,
            ObjectProvider<TenantDenialRecorder> recorders, ObjectProvider<TenantContext> tenantContexts) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.recorders = Objects.requireNonNull(recorders, "recorders");
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "tenantContexts");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Moved to DUE; sent again with the pinned versions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = Result.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: a dead-letter replay",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "No delivery with this id for the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Not DEAD_LETTERED, or not pinned to both versions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<?> replay(@PathVariable("deliveryId") UUID deliveryId, HttpServletRequest request) {
        String actor = actors.requireActor().userId().toString();
        return answer(actions.replay(deliveryId, actor), request);
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Moved to DUE now; sent again with the pinned versions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = Result.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: a dead-letter replay",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "No delivery with this id for the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "409", description = "Not RETRY_WAIT, or not pinned to both versions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{deliveryId}/retry")
    public ResponseEntity<?> retry(@PathVariable("deliveryId") UUID deliveryId, HttpServletRequest request) {
        String actor = actors.requireActor().userId().toString();
        return answer(actions.retry(deliveryId, actor), request);
    }

    private ResponseEntity<?> answer(Result result, HttpServletRequest request) {
        return switch (result.outcome()) {
            case MOVED_TO_DUE -> ResponseEntity.ok(result);
            case NOT_FOUND -> {
                recordPathIdDenial(request);
                yield ResponseEntity.status(404).body(Map.of("error", "not found"));
            }
            case WRONG_STATE -> ResponseEntity.status(409)
                    .body(Map.of("error", "wrong state", "state", result.state().name()));
            case NOT_PINNED -> ResponseEntity.status(409)
                    .body(Map.of("error", "not pinned", "state", result.state().name()));
        };
    }

    /** The one denied-attempt record a path-named foreign row earns: bound tenant, method, path - never the id. */
    private void recordPathIdDenial(HttpServletRequest request) {
        TenantDenialRecorder recorder = recorders.getIfAvailable();
        if (recorder == null) {
            return;
        }
        UUID bound = tenantContexts.getIfAvailable(() -> () -> Optional.empty()).currentTenantId().orElse(null);
        recorder.record(new DeniedTenantAttempt(bound, DeniedTenantAttempt.Channel.PATH_ID, request.getMethod(),
                request.getRequestURI(), Instant.now()));
    }
}
