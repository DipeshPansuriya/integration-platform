package io.integrationplatform.app.report;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Small controller adapter: exposes the aggregated report through a single endpoint, delegating all work to the facade.
 * No business logic lives here.
 */
@RestController
@RequestMapping("/api/v1/reports/facade")
public class ReportFacadeController {

    private final ReportFacade facade;

    private final ObjectProvider<TenantDenialRecorder> recorders;
    private final ObjectProvider<TenantContext> tenantContexts;

    public ReportFacadeController(ReportFacade facade, ObjectProvider<TenantDenialRecorder> recorders,
            ObjectProvider<TenantContext> tenantContexts) {
        this.facade = facade;
        this.recorders = Objects.requireNonNull(recorders, "recorders");
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "tenantContexts");
    }

    @GetMapping("/{trackingId}")
    public ResponseEntity<ReportFacade.Report> report(@PathVariable("trackingId") UUID trackingId,
            @RequestParam(value = "flowId", required = false) UUID flowId,
            @RequestParam(value = "day", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day, HttpServletRequest request) {
        ReportFacade.Report report = facade.report(trackingId, flowId, day);
        // a tracking id the bound tenant cannot see behaves like the timeline endpoint: 404, never a 200 with null
        if (report == null || report.timeline() == null) {
            // forged-tenant triage 2026-10-22: the denied attempt is recorded like MessageTrackingController does;
            // status and (empty) body are unchanged, so a foreign id and an unknown id stay indistinguishable
            recordPathIdDenial(request);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
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