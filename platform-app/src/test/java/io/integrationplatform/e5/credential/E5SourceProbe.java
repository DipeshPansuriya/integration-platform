package io.integrationplatform.e5.credential;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint for E5's credential suite (imported by {@code E5CredentialKeycloakContainerTest} only, never
 * scanned): a source system's call that reaches a handler answers 200 with the tenant its credential bound - the
 * observable "this token is accepted" a revoke must end. The common source API (SUBMIT_MESSAGE) is a later epic; no
 * role rule names this path, as for the forged-tenant suite's probe.
 *
 * <p>{@code @TestComponent}: excluded from component scanning, so no other suite's application has this endpoint (the
 * forged-tenant coverage guard would otherwise demand a matrix entry for it).
 */
@TestComponent
@RestController
class E5SourceProbe {

    static final String PATH = "/api/v1/e5-test-probe/source";

    private final TenantContext tenantContext;

    E5SourceProbe(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @GetMapping(PATH)
    ResponseEntity<Map<String, Object>> whoAmI() {
        return ResponseEntity.ok(Map.of("tenant", tenantContext.requireTenantId().toString()));
    }
}
