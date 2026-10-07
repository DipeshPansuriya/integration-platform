package io.integrationplatform.tenancy.port;

import java.util.Optional;

/**
 * The trusted pre-tenant path for source systems (ADR-007 Decision 2, ADR-009 "Update 2026-10-02"): resolves the pair
 * (issuer, client id from the {@code azp} claim) of a validated machine token through the {@code ipaas_client_bootstrap}
 * definer function. E4 seam: implemented and called by lane C (T-098).
 */
public interface ClientBootstrap {

    /**
     * Resolves a validated machine token's (issuer, client id) pair.
     *
     * @param issuer   the {@code iss} of a validated token
     * @param clientId the {@code azp} of a validated token
     * @return the binding for an ACTIVE credential of an ACTIVE tenant; empty for an unknown client, a PENDING or
     *         REVOKED credential, or an inactive tenant
     */
    Optional<ClientBinding> resolve(String issuer, String clientId);
}
