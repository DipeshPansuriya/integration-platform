package io.integrationplatform.tenancy.internal.principal;

import io.integrationplatform.tenancy.port.LoginBinding;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A request whose validated token was resolved to a user and tenant through the login bootstrap (ADR-007 Decision 2,
 * ADR-009 Decision 4). It lives only in the request's security context, so nothing of the binding survives the request.
 */
public final class TenantBoundAuthentication extends AbstractAuthenticationToken {

    private final Jwt token;
    private final LoginBinding binding;

    public TenantBoundAuthentication(JwtAuthenticationToken authenticated, LoginBinding binding) {
        super(authenticated.getAuthorities());
        this.token = authenticated.getToken();
        this.binding = Objects.requireNonNull(binding, "binding");
        setDetails(authenticated.getDetails());
        setAuthenticated(true);
    }

    /** The binding from our directory - the only source of the request's tenant. */
    public LoginBinding binding() {
        return binding;
    }

    /** Whether a client-supplied value names the bound tenant, by id (any case) or by code. */
    public static boolean namesBoundTenant(LoginBinding binding, String value) {
        if (value == null) {
            return false;
        }
        String candidate = value.strip();
        return candidate.equalsIgnoreCase(binding.tenantId().toString()) || candidate.equals(binding.tenantCode());
    }

    @Override
    public Object getPrincipal() {
        return token;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return token.getSubject();
    }
}
