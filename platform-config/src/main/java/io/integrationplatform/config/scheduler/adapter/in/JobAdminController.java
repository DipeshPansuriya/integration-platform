package io.integrationplatform.config.scheduler.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.scheduler.JobAdminService;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import io.integrationplatform.config.scheduler.PollingSchedulingBoundary;
import io.integrationplatform.domain.scheduler.PollingJobSurface.JobSummary;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E14 T-060 (ADR-012 section 5): the operator agent's job admin API - list, inspect, run now, pause, resume.
 * HTTP-only seam; the role of each route is in {@link JobEndpointRoles} (OPERATOR_AGENT only, so the role
 * interceptor refuses a source credential and every human role with 403 before this runs). The agent is the
 * authenticated name - the machine credential's client id.
 */
@RestController
@RequestMapping(JobAdminController.PATH)
public class JobAdminController {

    public static final String PATH = "/api/v1/admin/jobs";

    private final JobAdminService service;

    private final PollingSchedulingBoundary boundary;

    public JobAdminController(JobAdminService service, PollingSchedulingBoundary boundary) {
        this.service = Objects.requireNonNull(service, "service");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @GetMapping
    public List<JobDefinitionRow> list() {
        return service.list(agent());
    }

    /**
     * The polling summaries (identity and scheduling state only - ADR-012: no payload, no tenant id). Without
     * {@code since} the full list; with {@code ?since=<ISO-8601 instant>} only the jobs that ran, were paused or had
     * their enabled / paused state changed at or after it. A value that is not an instant is refused with 400.
     */
    @GetMapping("/summaries")
    public List<JobSummary> summaries(@RequestParam(name = "since", required = false) String since) {
        if (since == null) {
            return boundary.summaries();
        }
        return boundary.summariesSince(parseSince(since));
    }

    private static Instant parseSince(String since) {
        try {
            return Instant.parse(since.trim());
        } catch (DateTimeParseException e) {
            throw ConfigApiException.validation("since must be an ISO-8601 instant, e.g. 2026-10-22T08:30:00Z");
        }
    }

    @GetMapping("/{jobType}")
    public Map<String, Object> inspect(@PathVariable String jobType) {
        return service.inspect(agent(), jobType);
    }

    @PostMapping("/{jobType}/run")
    public ResponseEntity<Void> runNow(@PathVariable String jobType) {
        service.runNow(agent(), jobType);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{jobType}/pause")
    public ResponseEntity<Void> pause(@PathVariable String jobType) {
        service.pause(agent(), jobType);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobType}/resume")
    public ResponseEntity<Void> resume(@PathVariable String jobType) {
        service.resume(agent(), jobType);
        return ResponseEntity.noContent().build();
    }

    private static String agent() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
