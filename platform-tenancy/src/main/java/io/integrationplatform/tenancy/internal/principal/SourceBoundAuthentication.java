package io.integrationplatform.tenancy.internal.principal;

import io.integrationplatform.tenancy.port.ClientBinding;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A request whose validated machine token was resolved to a source system's credential through the client bootstrap
 * (ADR-007 Decisions 2 and 3, T-098): it binds the source's tenant and customer, never a human. Deliberately NOT a
 * {@link TenantBoundAuthentication}, so {@link RequestTenantContext#currentBinding()} stays empty and every endpoint
 * that needs a console user refuses a source. It lives only in the request's security context.
 */
public final class SourceBoundAuthentication extends AbstractAuthenticationToken {

    private final Jwt token;
    private final ClientBinding binding;

    public SourceBoundAuthentication(JwtAuthenticationToken authenticated, ClientBinding binding) {
        super(authenticated.getAuthorities());
        this.token = authenticated.getToken();
        this.binding = Objects.requireNonNull(binding, "binding");
        setDetails(authenticated.getDetails());
        setAuthenticated(true);
    }

    /** The credential's binding from our directory - the only source of the request's tenant and customer. */
    public ClientBinding binding() {
        return binding;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    /** The client id (the token's {@code azp}) the credential was found by. */
    @Override
    public String getName() {
        return token.getClaimAsString("azp");
    }
}
