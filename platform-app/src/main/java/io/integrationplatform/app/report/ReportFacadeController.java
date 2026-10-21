package io.integrationplatform.app.report;

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

    public ReportFacadeController(ReportFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/{trackingId}")
    public ResponseEntity<ReportFacade.Report> report(@PathVariable("trackingId") UUID trackingId,
            @RequestParam(value = "flowId", required = false) UUID flowId,
            @RequestParam(value = "day", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        ReportFacade.Report report = facade.report(trackingId, flowId, day);
        // a tracking id the bound tenant cannot see behaves like the timeline endpoint: 404, never a 200 with null
        if (report == null || report.timeline() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }
}