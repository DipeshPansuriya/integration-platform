package io.integrationplatform.app.activity;

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
 * Row327 T118 (E12-F2-S2, T-069): the tenant activity view - the bound tenant's audit events, newest first, filtered
 * by action and date, one bounded page at a time, and one event by id; read by {@link ActivityService}. READS ONLY.
 *
 * <p>The tenant never travels in the path (ADR-011 Decision 4): it comes from the bearer, bound by the security chain.
 * An event id the bound tenant cannot see is 404 with the config API's {@code {"error": "not found"}} body - never a
 * hint of whose row it is - and one {@code PATH_ID} denied-attempt record (bound tenant, method, path), exactly as
 * {@code MessageTrackingController}. The rule ({@code READ_MESSAGE_TIMELINE}) is declared in
 * {@link ActivityEndpointRoles}.
 */
@RestController
@RequestMapping(ActivityEndpointRoles.ACTIVITY)
public class ActivityController {

    private final ActivityService service;
    private final ConfigActors actors;
    private final ObjectProvider<TenantDenialRecorder> recorders;
    private final ObjectProvider<TenantContext> tenantContexts;

    public ActivityController(ActivityService service, ConfigActors actors,
            ObjectProvider<TenantDenialRecorder> recorders, ObjectProvider<TenantContext> tenantContexts) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.recorders = Objects.requireNonNull(recorders, "recorders");
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "tenantContexts");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ActivityService.ActivityPage.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: an activity read",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping
    public ResponseEntity<ActivityService.ActivityPage> list(
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        actors.requireActor();
        return ResponseEntity.ok(service.list(action, from, to, page, size));
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ActivityService.Activity.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: an activity read",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "No audit event with this id for the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/{eventId}")
    public ResponseEntity<?> event(@PathVariable("eventId") long eventId, HttpServletRequest request) {
        actors.requireActor();
        Optional<ActivityService.Activity> event = service.event(eventId);
        if (event.isPresent()) {
            return ResponseEntity.ok(event.get());
        }
        recordPathIdDenial(request);
        return ResponseEntity.status(404).body(Map.of("error", "not found"));
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
