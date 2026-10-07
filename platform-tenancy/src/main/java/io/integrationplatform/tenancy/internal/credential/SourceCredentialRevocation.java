package io.integrationplatform.tenancy.internal.credential;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import io.integrationplatform.tenancy.port.SourceCredentialStore.PendingDisable;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-186, the revoke order of ADR-007 Decision 3 (S-j): the platform row FIRST, then the identity provider.
 *
 * <ol>
 *   <li>One tenant-bound transaction marks the credential REVOKED and writes {@code idp_disable_pending} ahead - from
 *       that commit on the client bootstrap refuses the credential, so a still-unexpired token is refused at once
 *       (401), whatever the identity provider does next. Writing the flag ahead (instead of only after a failed call)
 *       also covers a crash between the commit and the provider call.</li>
 *   <li>Then the client is disabled at the identity provider ({@link IdentityProviderAdmin}, R13 controls).</li>
 *   <li>Confirmed: a second transaction clears the flag. Failed: the flag stays and the credential reconciliation
 *       ({@link IdpDisableReconciliation}) retries it with backoff until the provider confirms; the console shows the
 *       pending state (E5).</li>
 * </ol>
 *
 * Runs for the bound tenant only (the tenant admin's request; the endpoint and its REVOKE_SOURCE_CREDENTIAL role rule
 * are E5's).
 */
public final class SourceCredentialRevocation {

    /** What the revoke achieved. */
    public enum Outcome {
        /** Revoked on the platform and disabled at the identity provider. */
        REVOKED,
        /** Revoked on the platform (effective now); the identity-provider disable is pending for the reconciliation. */
        IDP_DISABLE_PENDING,
        /** No such credential of the bound tenant, or already revoked: nothing changed, nothing called. */
        NOT_FOUND
    }

    private static final Logger LOG = LoggerFactory.getLogger(SourceCredentialRevocation.class);

    private final SourceCredentialStore store;
    private final TransactionOperations transactions;
    private final IdentityProviderAdmin identityProvider;

    public SourceCredentialRevocation(SourceCredentialStore store, TransactionOperations transactions,
            IdentityProviderAdmin identityProvider) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
    }

    public Outcome revoke(UUID credentialId) {
        PendingDisable revoked = transactions.execute(status -> {
            if (!store.markRevoked(credentialId) || !store.setIdpDisablePending(credentialId, true)) {
                return null;
            }
            return store.findIdpDisablePending().stream()
                    .filter(pending -> pending.credentialId().equals(credentialId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("the revoked credential is not pending"));
        });
        if (revoked == null) {
            return Outcome.NOT_FOUND;
        }
        if (!revoked.issuer().equals(identityProvider.issuer())) {
            pending(credentialId, "other_issuer");
            return Outcome.IDP_DISABLE_PENDING;
        }
        try {
            identityProvider.disableClient(revoked.clientId());
        } catch (IdentityProviderAdmin.IdentityProviderAdminException unavailable) {
            pending(credentialId, "identity_provider_unavailable");
            return Outcome.IDP_DISABLE_PENDING;
        }
        transactions.executeWithoutResult(status -> store.setIdpDisablePending(credentialId, false));
        return Outcome.REVOKED;
    }

    private static void pending(UUID credentialId, String reason) {
        LOG.atWarn()
                .addKeyValue("event", "credential.idp_disable_pending")
                .addKeyValue("credential", credentialId)
                .addKeyValue("reason", reason)
                .log("event=credential.idp_disable_pending credential={} reason={}", credentialId, reason);
    }
}
