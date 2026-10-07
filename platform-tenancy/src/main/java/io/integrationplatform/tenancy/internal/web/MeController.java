package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/v1/me - the bound user and tenant, straight from the login binding (no further database read). The one
 * real read endpoint of E1; lane D's forged-tenant matrix runs against it.
 */
@RestController
class MeController {

    record Me(UUID userId, UUID tenantId, String tenantCode, String timeZone) {
    }

    private final RequestTenantContext tenantContext;

    MeController(RequestTenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @GetMapping("/api/v1/me")
    Me me() {
        // A source system's call binds a tenant but no user (E4 T-098): it is not a console user - 403.
        LoginBinding binding = tenantContext.currentBinding()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        return new Me(binding.userId(), binding.tenantId(), binding.tenantCode(), binding.timeZone().getId());
    }
}
