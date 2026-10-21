package io.integrationplatform.tenancy.internal.principal;

import io.integrationplatform.tenancy.port.ClientBinding;
import io.integrationplatform.tenancy.port.ClientBootstrap;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
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
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant from our directory (ADR-007 Decision 2, ADR-009 Decision 4): the (issuer, subject) of the
 * validated token goes to {@link LoginBootstrap}; no binding is 403 with no tenant data read. A {@code tenant} claim is
 * only cross-checked - a mismatch is 403 and recorded as TOKEN_CLAIM. Runs after the bearer-token filter, so a token
 * put in the security context by spring-security-test's {@code jwt()} takes the same path as a real one.
 *
 * <p>Source systems (E4 T-098, ADR-007 Decisions 2 and 3), only when built with a {@link ClientBootstrap}: humans are
 * resolved first; a token with no human binding that carries an {@code azp} is a machine credential - the pair
 * (issuer, azp) goes to the client bootstrap, which returns ACTIVE credentials of ACTIVE tenants only. A bound source
 * gets a {@link SourceBoundAuthentication} (its tenant and customer, never a human); a credential that is not ACTIVE
 * (revoked, pending, unknown) is 401 - the token is valid but no longer accepted - and recorded as
 * INACTIVE_CREDENTIAL. Built without one (the two-argument constructor) the behaviour is the human-only one above.
 *
 * <p>Operator agents (E14 T-127, ADR-012 section 5), only when built with a {@link SchedulerRegistry}: before the
 * source path, an (issuer, azp) registered ACTIVE in {@code scheduler.operator_credential} gets an
 * {@link OperatorAgentAuthentication}. An operator agent is platform-scoped: no tenant is stamped, so tenant-owned
 * (RLS) queries fail closed, and the role interceptor admits it only to OPERATOR_AGENT operations. A token of one that
 * names a tenant is 403 (TOKEN_CLAIM); a revoked or unknown one with no source binding is 401 as above.
 */
public final class PrincipalTenantResolver extends OncePerRequestFilter {

    static final String TENANT_CLAIM = "tenant";

    static final String CLIENT_ID_CLAIM = "azp";

    private final LoginBootstrap bootstrap;
    private final ClientBootstrap clients;
    private final SchedulerRegistry operators;
    private final TenantDenialRecorder recorder;
    private final BearerTokenAuthenticationEntryPoint inactiveCredential = new BearerTokenAuthenticationEntryPoint();

    /** Humans only: a token with no login binding is 403 whatever it carries. */
    public PrincipalTenantResolver(LoginBootstrap bootstrap, TenantDenialRecorder recorder) {
        this(bootstrap, null, recorder);
    }

    /** Humans first, then source systems through {@code clients} (T-098); {@code null} means humans only. */
    public PrincipalTenantResolver(LoginBootstrap bootstrap, ClientBootstrap clients, TenantDenialRecorder recorder) {
        this(bootstrap, clients, null, recorder);
    }

    /** Humans, then operator agents through {@code operators} (T-127), then sources; either {@code null} is skipped. */
    public PrincipalTenantResolver(LoginBootstrap bootstrap, ClientBootstrap clients, SchedulerRegistry operators,
            TenantDenialRecorder recorder) {
        this.bootstrap = bootstrap;
        this.clients = clients;
        this.operators = operators;
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
            String clientId = clients == null && operators == null ? null : jwt.getClaimAsString(CLIENT_ID_CLAIM);
            if (clientId == null || clientId.isBlank()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            if (operators != null && operators.operatorCredentialActive(jwt.getClaimAsString(JwtClaimNames.ISS),
                    clientId)) {
                bindOperator(token, clientId, request, response, chain);
            } else if (clients != null) {
                bindSource(token, clientId, request, response, chain);
            } else {
                refuseInactive(request, response);
            }
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

    /** The machine path: the credential's binding or 401; a forged tenant claim is 403 as for humans. */
    private void bindSource(JwtAuthenticationToken token, String clientId, HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
        Jwt jwt = token.getToken();
        Optional<ClientBinding> resolved = clients.resolve(jwt.getClaimAsString(JwtClaimNames.ISS), clientId);
        if (resolved.isEmpty()) {
            refuseInactive(request, response);
            return;
        }
        ClientBinding binding = resolved.get();
        String claimed = jwt.getClaimAsString(TENANT_CLAIM);
        if (claimed != null
                && !TenantBoundAuthentication.namesTenant(binding.tenantId(), binding.tenantCode(), claimed)) {
            recorder.record(new DeniedTenantAttempt(binding.tenantId(), DeniedTenantAttempt.Channel.TOKEN_CLAIM,
                    request.getMethod(), request.getRequestURI(), Instant.now()));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        SecurityContext bound = holder.createEmptyContext();
        bound.setAuthentication(new SourceBoundAuthentication(token, binding));
        holder.setContext(bound);
        chain.doFilter(request, response);
    }

    /** The operator path: platform-scoped, so any tenant claim is a forgery attempt and 403. */
    private void bindOperator(JwtAuthenticationToken token, String clientId, HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (token.getToken().getClaimAsString(TENANT_CLAIM) != null) {
            recorder.record(new DeniedTenantAttempt(null, DeniedTenantAttempt.Channel.TOKEN_CLAIM,
                    request.getMethod(), request.getRequestURI(), Instant.now()));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
        SecurityContext bound = holder.createEmptyContext();
        bound.setAuthentication(new OperatorAgentAuthentication(token, clientId));
        holder.setContext(bound);
        chain.doFilter(request, response);
    }

    /** A valid token whose credential is not (or no longer) ACTIVE: 401, recorded as INACTIVE_CREDENTIAL. */
    private void refuseInactive(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        recorder.record(new DeniedTenantAttempt(null, DeniedTenantAttempt.Channel.INACTIVE_CREDENTIAL,
                request.getMethod(), request.getRequestURI(), Instant.now()));
        SecurityContextHolder.getContextHolderStrategy().clearContext();
        inactiveCredential.commence(request, response, new InvalidBearerTokenException("The credential is not active"));
    }
}
