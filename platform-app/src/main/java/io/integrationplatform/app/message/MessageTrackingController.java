package io.integrationplatform.app.message;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
 * E13 N1 (T110 RED skeleton): the message timeline and failures reads - not built yet.
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

    @GetMapping("/{trackingId}/timeline")
    public ResponseEntity<?> timeline(@PathVariable("trackingId") UUID trackingId, HttpServletRequest request) {
        throw new UnsupportedOperationException("T110 RED: the timeline endpoint is not built yet");
    }

    @GetMapping("/failures")
    public ResponseEntity<List<MessageTrackingService.Failure>> failures(
            @RequestParam(value = "flowId", required = false) UUID flowId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        throw new UnsupportedOperationException("T110 RED: the failures endpoint is not built yet");
    }
}
