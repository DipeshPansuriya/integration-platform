package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.TENANT_A;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.TENANT_B;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Test-only read endpoint over rows of tenant A and tenant B (E1-F1-S1 "Given rows for tenant A and tenant B"). It reads
 * the tenant only from {@link TenantContext}, as every real endpoint must; a record id of another tenant answers 404
 * and is recorded as PATH_ID (lane D's probe does the same end to end on PostgreSQL).
 */
@RestController
@RequestMapping("/api/test/rows")
class TenantRowsProbeController {

    record Row(UUID id, UUID tenantId, String name) {
    }

    static final Row A1 = new Row(UUID.fromString("a2000000-0000-4000-8000-0000000000a1"), TENANT_A, "a-one");
    static final Row A2 = new Row(UUID.fromString("a2000000-0000-4000-8000-0000000000a2"), TENANT_A, "a-two");
    static final Row B1 = new Row(UUID.fromString("b2000000-0000-4000-8000-0000000000b1"), TENANT_B, "b-one");
    static final List<Row> ROWS = List.of(A1, A2, B1);

    /** How often tenant data was read - "no tenant data is read" is asserted on it. */
    static final AtomicInteger READS = new AtomicInteger();

    private final ObjectProvider<TenantContext> tenantContext;
    private final ObjectProvider<TenantDenialRecorder> recorder;

    TenantRowsProbeController(ObjectProvider<TenantContext> tenantContext,
            ObjectProvider<TenantDenialRecorder> recorder) {
        this.tenantContext = tenantContext;
        this.recorder = recorder;
    }

    @GetMapping
    List<Row> list() {
        return rowsOfBoundTenant();
    }

    @GetMapping("/{id}")
    ResponseEntity<Row> one(@PathVariable UUID id, HttpServletRequest request) {
        UUID bound = tenantContext.getObject().requireTenantId();
        READS.incrementAndGet();
        Row row = ROWS.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
        if (row == null || !row.tenantId().equals(bound)) {
            if (row != null) {
                // The route pattern, never the raw path: the foreign id is attacker input and is not logged.
                String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                recorder.getObject().record(new DeniedTenantAttempt(
                        bound, DeniedTenantAttempt.Channel.PATH_ID, request.getMethod(), pattern, Instant.now()));
            }
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(row);
    }

    @PostMapping("/search")
    Map<String, Object> search(@RequestBody Map<String, Object> body) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("rows", rowsOfBoundTenant());
        answer.put("echoName", body.get("name"));
        return answer;
    }

    private List<Row> rowsOfBoundTenant() {
        UUID bound = tenantContext.getObject().requireTenantId();
        READS.incrementAndGet();
        return ROWS.stream().filter(r -> r.tenantId().equals(bound)).toList();
    }
}
