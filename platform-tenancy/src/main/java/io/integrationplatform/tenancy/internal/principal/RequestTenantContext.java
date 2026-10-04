package io.integrationplatform.tenancy.internal.principal;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The request implementation of {@link TenantContext} (T-001): the tenant bound by {@link PrincipalTenantResolver} to
 * the current request's security context. It keeps no state of its own; the security context is cleared at the end of
 * every request, so no binding survives it.
 */
public final class RequestTenantContext implements TenantContext {

    @Override
    public Optional<UUID> currentTenantId() {
        return currentBinding().map(LoginBinding::tenantId);
    }

    /** The full binding (user, tenant, time zone) of the current request, or empty when none is bound. */
    public Optional<LoginBinding> currentBinding() {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        return authentication instanceof TenantBoundAuthentication bound
                ? Optional.of(bound.binding())
                : Optional.empty();
    }
}
