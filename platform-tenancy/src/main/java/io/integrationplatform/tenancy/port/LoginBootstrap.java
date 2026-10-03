package io.integrationplatform.tenancy.port;

import java.util.Optional;

/**
 * The one trusted path across the pre-tenant boundary for humans (ADR-009 Decision 3, ADR-007 Decision 2): resolves the
 * pair (issuer, subject) of a validated token to its user and tenant through the {@code ipaas_login_bootstrap} definer
 * function.
 *
 * <p>E1 seam: implemented by lane C (T-157, JDBC adapter); called by lane B (T-001, principal resolver).
 */
public interface LoginBootstrap {

    /**
     * Resolves a validated token's (issuer, subject) pair.
     *
     * @param issuer  the {@code iss} of a validated token
     * @param subject the {@code sub} of a validated token
     * @return the binding for an ACTIVE user of an ACTIVE tenant; empty for an unknown subject, an inactive user or an
     *         inactive tenant
     */
    Optional<LoginBinding> resolve(String issuer, String subject);
}
