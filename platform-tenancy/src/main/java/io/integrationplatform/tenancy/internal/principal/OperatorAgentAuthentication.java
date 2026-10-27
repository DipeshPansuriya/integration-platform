package io.integrationplatform.tenancy.internal.principal;

import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * E14 seam (arjun-fullstack, F8; ADR-012 section 5, Accepted by the project owner 2026-10-12): a request whose validated machine
 * token was resolved to an entry of {@code scheduler.operator_credential} - the platform's first no-tenant principal.
 * Deliberately NEITHER a {@link TenantBoundAuthentication} nor a {@link SourceBoundAuthentication}: it binds no tenant
 * and no source system, so every tenant-owned query errors under row-level security and every existing operation
 * refuses it by kind - only the job-admin and alert operations (ADR-012 Decision 5: LIST_JOBS, INSPECT_JOB, RUN_JOB_NOW,
 * PAUSE_JOB, RESUME_JOB) and the alert read/acknowledge accept the kind. Its attack suite is T-147 (E15). Signature-only
 * seam: the resolver that builds it from the {@code scheduler.operator_credential} row is lane B's (T-056).
 */
public final class OperatorAgentAuthentication extends AbstractAuthenticationToken {

    private final Jwt token;
    private final String clientId;

    public OperatorAgentAuthentication(JwtAuthenticationToken authenticated, String clientId) {
        super(authenticated.getAuthorities());
        this.token = authenticated.getToken();
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        setDetails(authenticated.getDetails());
        setAuthenticated(true);
    }

    /** The agent's Keycloak client id (the token's {@code azp}), as found in scheduler.operator_credential. */
    public String clientId() {
        return clientId;
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
        return token.getClaimAsString("azp");
    }
}