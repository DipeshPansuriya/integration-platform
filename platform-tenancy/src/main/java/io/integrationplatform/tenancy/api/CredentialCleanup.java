package io.integrationplatform.tenancy.api;

/**
 * E14 T-203 (ADR-007 Decision 3, ADR-012 section 2): one source-credential clean-up pass of the BOUND tenant - the
 * unfinished issues older than the pending timeout and the rotations past their grace window are revoked at the
 * platform (flagged {@code idp_disable_pending}), then every flagged client of this provider is disabled at the
 * identity provider; a client the provider does not confirm stays flagged and is retried with backoff.
 *
 * <p>Takes no tenant: the tenant is the one bound on the thread (the scheduler job binds each tenant of
 * {@code ipaas_active_tenants()} in turn), and every row read or written is that tenant's by RLS and the tenant
 * predicate. With no tenant bound it refuses ({@code NoTenantBoundException}).
 */
public interface CredentialCleanup {

    /** Runs one pass for the bound tenant and reports what it did. */
    Result cleanUpBoundTenant();

    /**
     * @param abandoned    stale PENDING credentials revoked
     * @param expired      rotated credentials revoked at the end of their grace window
     * @param cleared      clients the identity provider confirmed disabled
     * @param stillPending flagged clients tried and not confirmed (or of another issuer) - retried next run
     * @param waiting      flagged clients left for their backoff
     */
    record Result(int abandoned, int expired, int cleared, int stillPending, int waiting) {
    }
}
