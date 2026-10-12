package io.integrationplatform.tenancy.port;

import io.integrationplatform.tenancy.port.SourceCredentialStore.Credential;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The source-credential lifecycle a tenant admin drives (E5 T-103; ADR-007 Decision 3): issue, rotate and revoke, for
 * the bound tenant. Implemented next to the table, the identity-provider adapter and E4's revoke order (tenancy
 * {@code internal.credential}); the configuration module's HTTP API calls it after its own checks (the source system
 * is the tenant's, the caller holds the role).
 *
 * <ul>
 *   <li><b>Issue</b>: the row is written PENDING, the confidential client is created in ONE identity-provider call (the
 *       platform API audience scope always named), then the row becomes ACTIVE - nothing is ever half-created. The
 *       secret is answered once, in {@link Issued}, and never stored, returned again or logged.</li>
 *   <li><b>Rotate</b> = issue a second credential for the same source system and customer; the old one keeps working
 *       for the grace window (default 7 days) and is then refused by the bootstrap and revoked by the
 *       reconciliation.</li>
 *   <li><b>Revoke</b>: E4's revoke-first order - the row REVOKED (refused from that commit on), then the client
 *       disabled; a failed disable leaves {@code idp_disable_pending} for the reconciliation.</li>
 * </ul>
 * An identity-provider failure is an {@link IdentityProviderAdmin.IdentityProviderAdminException} with a clear message.
 */
public interface SourceCredentialAdmin {

    /**
     * Issues a credential of the bound tenant's source system.
     *
     * @throws IdentityProviderAdmin.IdentityProviderAdminException when the client could not be created (or no admin
     *     client is configured on this instance); no ACTIVE or PENDING row stays behind
     */
    Issued issue(UUID sourceSystemId, String customerId);

    /**
     * Issues the replacement of an ACTIVE credential and schedules the old one's stop after the grace window.
     *
     * @throws CredentialStateException when the credential is unknown to the tenant, not ACTIVE, or already rotating
     * @throws IdentityProviderAdmin.IdentityProviderAdminException when the replacement could not be created; the old
     *     credential is then left exactly as it was
     */
    Rotated rotate(UUID credentialId);

    /** Revokes a credential of the bound tenant, platform first. */
    RevokeOutcome revoke(UUID credentialId);

    /** The bound tenant's credentials of one source system, oldest first (metadata only). */
    List<Credential> list(UUID sourceSystemId);

    /** One credential of the bound tenant (metadata only). */
    Optional<Credential> find(UUID credentialId);

    /** What a revoke achieved. */
    enum RevokeOutcome {
        /** Revoked on the platform and disabled at the identity provider. */
        REVOKED,
        /** Revoked on the platform (effective now); the client disable waits for the reconciliation. */
        IDP_DISABLE_PENDING,
        /** No such credential of the bound tenant: nothing changed, nothing called. */
        NOT_FOUND,
        /** Already revoked: nothing changed, nothing called. */
        ALREADY_REVOKED
    }

    /** An issued credential; the secret lives in memory only and is never part of {@link #toString()}. */
    record Issued(UUID credentialId, UUID sourceSystemId, String customerId, String clientId, String secret) {

        public Issued {
            Objects.requireNonNull(credentialId, "credentialId");
            Objects.requireNonNull(sourceSystemId, "sourceSystemId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(secret, "secret");
        }

        @Override
        public String toString() {
            return "Issued[" + credentialId + ", " + clientId + "]";
        }
    }

    /** A rotation: the replacement (with its secret, once) and when the replaced credential stops. */
    record Rotated(Issued replacement, UUID replacedCredentialId, Instant replacedRevokeAfter) {

        public Rotated {
            Objects.requireNonNull(replacement, "replacement");
            Objects.requireNonNull(replacedCredentialId, "replacedCredentialId");
            Objects.requireNonNull(replacedRevokeAfter, "replacedRevokeAfter");
        }
    }

    /** A rotate the credential's state does not allow; the message names the credential, never a secret. */
    final class CredentialStateException extends RuntimeException {

        /** Why the credential cannot be rotated. */
        public enum Reason { NOT_FOUND, NOT_ACTIVE, ALREADY_ROTATING }

        private final Reason reason;

        public CredentialStateException(Reason reason, UUID credentialId) {
            super("The credential " + credentialId + " cannot be rotated: " + reason);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }
}
