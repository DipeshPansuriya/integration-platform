package io.integrationplatform.tenantdenial;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.integrationplatform.tenantdenial.ProbeRowReader.ProbeRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only tenant-owned read API over the probe_row fixture (T-002): stands in for the tenant data endpoints later
 * epics add, so E1's denial suite has tenant rows to protect. It never reads a tenant from the request - only from
 * {@link TenantContext}. An id not visible under the bound tenant answers 404 and is recorded as a {@code PATH_ID}
 * denied attempt (the forged value itself is not recorded).
 *
 * <p>{@code @TestComponent}: excluded from component scanning; only the denial suite imports it.
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/test-probe/rows")
public class TestProbeController {

    private final ProbeRowReader reader;
    private final ObjectProvider<TenantContext> tenantContext;
    private final ObjectProvider<TenantDenialRecorder> recorder;

    public TestProbeController(ProbeRowReader reader, ObjectProvider<TenantContext> tenantContext,
            ObjectProvider<TenantDenialRecorder> recorder) {
        this.reader = reader;
        this.tenantContext = tenantContext;
        this.recorder = recorder;
    }

    @GetMapping
    public List<ProbeRow> list() {
        return reader.all();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProbeRow> one(@PathVariable UUID id, HttpServletRequest request) {
        return reader.byId(id).map(ResponseEntity::ok).orElseGet(() -> {
            TenantDenialRecorder denial = recorder.getIfAvailable();
            if (denial != null) {
                UUID bound = tenantContext.getObject().currentTenantId().orElse(null);
                denial.record(new DeniedTenantAttempt(bound, DeniedTenantAttempt.Channel.PATH_ID, request.getMethod(),
                        request.getRequestURI(), Instant.now()));
            }
            return ResponseEntity.notFound().build();
        });
    }

    @PostMapping("/search")
    public List<ProbeRow> search(@RequestBody ProbeSearch search) {
        return reader.byLabelPrefix(search.labelPrefix());
    }

    /** The search body. Any tenant field a caller adds is ignored here; lane B's chain records it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProbeSearch(String labelPrefix) {
    }
}
