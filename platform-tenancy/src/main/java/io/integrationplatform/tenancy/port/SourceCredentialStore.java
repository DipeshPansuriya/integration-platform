package io.integrationplatform.tenancy.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The state changes of a source credential that the revoke order of ADR-007 Decision 3 (S-j) needs: the platform row is
 * revoked FIRST - from that commit on the client bootstrap refuses the credential - and only then is the client
 * disabled at the identity provider; a failed disable leaves {@code idp_disable_pending} for the credential
 * reconciliation to retry.
 *
 * <p>Every method runs in the caller's open, tenant-bound transaction (R08, R11) and only ever sees the bound tenant's
 * rows; 0 rows changed is reported as {@code false}, never as an exception. E4 seam: implemented by lane C (T-098);
 * called by lane B (T-186, revoke and reconciliation) and E5 T-103.
 */
public interface SourceCredentialStore {

    /**
     * {@code PENDING/ACTIVE -> REVOKED} with {@code revoked_at} = the transaction time.
     *
     * @return {@code false} when the credential is unknown to the bound tenant or already revoked
     */
    boolean markRevoked(UUID credentialId);

    /**
     * Sets or clears {@code idp_disable_pending} on a REVOKED credential.
     *
     * @return {@code false} when the credential is unknown to the bound tenant or not revoked
     */
    boolean setIdpDisablePending(UUID credentialId, boolean pending);

    /** The bound tenant's revoked credentials whose identity-provider client still has to be disabled, oldest first. */
    List<PendingDisable> findIdpDisablePending();

    /** A revoked credential whose client the reconciliation must still disable at the identity provider. */
    record PendingDisable(UUID credentialId, String issuer, String clientId) {

        public PendingDisable {
            Objects.requireNonNull(credentialId, "credentialId");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(clientId, "clientId");
        }
    }
}
