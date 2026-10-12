package io.integrationplatform.tenancy.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 *
 * <p>E5 T-103 adds the issue and rotation half (additive): a credential is written PENDING, becomes ACTIVE once its
 * client exists at the identity provider, and a rotated one carries {@code revoke_after} (V2_3_6) - the client
 * bootstrap refuses it from that moment, and the reconciliation revokes it. Times are the database's ({@code now()}),
 * the same clock the bootstrap compares against.
 */
public interface SourceCredentialStore {

    /** Writes a PENDING credential of the bound tenant (ADR-007 Decision 3: before the identity provider is called). */
    default void insertPending(NewCredential credential) {
        throw unsupported();
    }

    /**
     * {@code PENDING -> ACTIVE}: the client exists at the identity provider.
     *
     * @return {@code false} when the credential is unknown to the bound tenant or not PENDING
     */
    default boolean markActive(UUID credentialId) {
        throw unsupported();
    }

    /** One credential of the bound tenant (metadata only - a secret is never stored). */
    default Optional<Credential> find(UUID credentialId) {
        throw unsupported();
    }

    /** The bound tenant's credentials of one source system, oldest first. */
    default List<Credential> findBySourceSystem(UUID sourceSystemId) {
        throw unsupported();
    }

    /**
     * Schedules the stop of an ACTIVE credential being rotated: {@code revoke_after = now() + grace}, set once.
     *
     * @return {@code false} when the credential is unknown, not ACTIVE or already scheduled
     */
    default boolean scheduleRevoke(UUID credentialId, Duration grace) {
        throw unsupported();
    }

    /**
     * A PENDING credential older than {@code olderThan} (the issue never finished) becomes REVOKED with
     * {@code idp_disable_pending}, so the reconciliation disables any client it may have left. Called by the
     * credential reconciliation job (E14 T-203) per bound tenant.
     *
     * @return the number of rows revoked
     */
    default int abandonStalePending(Duration olderThan) {
        throw unsupported();
    }

    /**
     * An ACTIVE credential whose {@code revoke_after} has passed becomes REVOKED with {@code idp_disable_pending}
     * (platform first, then the client). Called by the credential reconciliation job (E14 T-203) per bound tenant.
     *
     * @return the number of rows revoked
     */
    default int revokeExpiredRotations() {
        throw unsupported();
    }

    /**
     * The E5 additions are defaults so E4's revoke-only stores (test doubles of the revoke order) keep compiling; the
     * PostgreSQL adapter implements every method.
     */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("this source-credential store implements the E4 revoke order only");
    }

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

    /** A credential to write PENDING; the caller chooses the id (the client id is derived from it). */
    record NewCredential(UUID id, UUID sourceSystemId, String customerId, String issuer, String clientId) {

        public NewCredential {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sourceSystemId, "sourceSystemId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(clientId, "clientId");
        }
    }

    /** A stored credential's metadata; there is no secret to show - it is never stored. */
    record Credential(UUID id, UUID sourceSystemId, String customerId, String issuer, String clientId, String status,
            boolean idpDisablePending, Instant createdAt, Instant revokedAt, Instant revokeAfter) {

        public Credential {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sourceSystemId, "sourceSystemId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(status, "status");
        }
    }
}
