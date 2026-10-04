package io.integrationplatform.tenancy.internal.principal;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant from our directory (ADR-007 Decision 2, ADR-009 Decision 4): the (issuer, subject) of the
 * validated token goes to {@link LoginBootstrap}; no binding is 403 with no tenant data read. A {@code tenant} claim is
 * only cross-checked - a mismatch is 403 and recorded as TOKEN_CLAIM. Runs after the bearer-token filter, so a token
 * put in the security context by spring-security-test's {@code jwt()} takes the same path as a real one.
 */
public final class PrincipalTenantResolver extends OncePerRequestFilter {

    static final String TENANT_CLAIM = "tenant";

    private final LoginBootstrap bootstrap;
    private final TenantDenialRecorder recorder;

    public PrincipalTenantResolver(LoginBootstrap bootstrap, TenantDenialRecorder recorder) {
        this.bootstrap = bootstrap;
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
        Authentication authentication = holder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        Optional<LoginBinding> resolved = bootstrap.resolve(jwt.getClaimAsString(JwtClaimNames.ISS), jwt.getSubject());
        if (resolved.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        LoginBinding binding = resolved.get();
        String claimed = jwt.getClaimAsString(TENANT_CLAIM);
        if (claimed != null && !TenantBoundAuthentication.namesBoundTenant(binding, claimed)) {
            recorder.record(new DeniedTenantAttempt(binding.tenantId(), DeniedTenantAttempt.Channel.TOKEN_CLAIM,
                    request.getMethod(), request.getRequestURI(), Instant.now()));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        SecurityContext bound = holder.createEmptyContext();
        bound.setAuthentication(new TenantBoundAuthentication(token, binding));
        holder.setContext(bound);
        chain.doFilter(request, response);
    }
}
