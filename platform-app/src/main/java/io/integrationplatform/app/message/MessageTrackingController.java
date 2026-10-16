package io.integrationplatform.app.message;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E13 N1 (T110; T-137 API narrow + the E12 failure-diagnosis read side): the message timeline by tracking id and the
 * failures list per flow and day, read by {@link MessageTrackingService}. READS ONLY: no body, nothing written.
 *
 * <p>The tenant never travels in the path (ADR-011 Decision 4): it comes from the bearer, bound by the security chain,
 * and every answer is the bound tenant's rows. A tracking id the bound tenant cannot see is 404 with the config API's
 * {@code {"error": "not found"}} body - never a hint of whose row it is - and, like {@code ConfigApiExceptionHandler}
 * for a path-named foreign row, one {@code PATH_ID} denied-attempt record (bound tenant, method, path). The rule
 * ({@code READ_MESSAGE_TIMELINE}) is declared in {@link MessageEndpointRoles}.
 */
@RestController
@RequestMapping(MessageEndpointRoles.MESSAGES)
public class MessageTrackingController {

    private final MessageTrackingService service;
    private final ConfigActors actors;
    private final ObjectProvider<TenantDenialRecorder> recorders;
    private final ObjectProvider<TenantContext> tenantContexts;

    public MessageTrackingController(MessageTrackingService service, ConfigActors actors,
            ObjectProvider<TenantDenialRecorder> recorders, ObjectProvider<TenantContext> tenantContexts) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.recorders = Objects.requireNonNull(recorders, "recorders");
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "tenantContexts");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = MessageTrackingService.Timeline.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: a message-timeline read",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "No message with this tracking id for the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{trackingId}/timeline")
    public ResponseEntity<?> timeline(@PathVariable("trackingId") UUID trackingId, HttpServletRequest request) {
        actors.requireActor();
        Optional<MessageTrackingService.Timeline> timeline = service.timeline(trackingId);
        if (timeline.isPresent()) {
            return ResponseEntity.ok(timeline.get());
        }
        recordPathIdDenial(request);
        return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: a message-timeline read",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/failures")
    public ResponseEntity<List<MessageTrackingService.Failure>> failures(
            @RequestParam(value = "flowId", required = false) UUID flowId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        actors.requireActor();
        return ResponseEntity.ok(service.failures(flowId, date));
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
