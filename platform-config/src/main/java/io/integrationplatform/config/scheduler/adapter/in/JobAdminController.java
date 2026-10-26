package io.integrationplatform.config.scheduler.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.scheduler.JobAdminService;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private final Supplier<JobAdminService> service;

    public JobAdminController(JobAdminService service) {
        Objects.requireNonNull(service, "service");
        this.service = () -> service;
    }

    /** Always registered (the role-rule completeness scan needs it); without db-scheduler every call is a 503. */
    @Autowired
    public JobAdminController(ObjectProvider<JobAdminService> services) {
        Objects.requireNonNull(services, "services");
        this.service = () -> {
            JobAdminService s = services.getIfAvailable();
            if (s == null) {
                throw ConfigApiException.refusal(503, "SCHEDULER_NOT_RUNNING");
            }
            return s;
        };
    }

    @GetMapping
    public List<JobDefinitionRow> list() {
        return service.get().list(agent());
    }

    @GetMapping("/{jobType}")
    public Map<String, Object> inspect(@PathVariable String jobType) {
        return service.get().inspect(agent(), jobType);
    }

    @PostMapping("/{jobType}/run")
    public ResponseEntity<Void> runNow(@PathVariable String jobType) {
        service.get().runNow(agent(), jobType);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{jobType}/pause")
    public ResponseEntity<Void> pause(@PathVariable String jobType) {
        service.get().pause(agent(), jobType);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobType}/resume")
    public ResponseEntity<Void> resume(@PathVariable String jobType) {
        service.get().resume(agent(), jobType);
        return ResponseEntity.noContent().build();
    }

    private static String agent() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
