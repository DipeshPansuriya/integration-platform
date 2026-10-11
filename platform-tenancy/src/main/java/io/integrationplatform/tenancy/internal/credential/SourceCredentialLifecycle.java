package io.integrationplatform.tenancy.internal.credential;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.CreatedClient;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.IdentityProviderAdminException;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import io.integrationplatform.tenancy.port.SourceCredentialStore.Credential;
import io.integrationplatform.tenancy.port.SourceCredentialStore.NewCredential;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E5 T-103 (ADR-007 Decision 3): the source-credential lifecycle of the bound tenant - issue, rotate, revoke - beside
 * the table, the identity-provider adapter and E4's revoke order ({@link SourceCredentialRevocation}, reused as is).
 *
 * <p>Issue: (1) one transaction writes the row PENDING; (2) the identity provider creates the confidential client in
 * ONE call (the adapter always names the platform API audience scope); (3) a second transaction makes the row ACTIVE.
 * A failed create - refused, unreachable, or a timeout after sending (the client may exist) - revokes the row with
 * {@code idp_disable_pending}, so no ACTIVE or PENDING row stays and the reconciliation disables any client the
 * provider may have kept; the caller gets the provider's clear message. A crash between (2) and (3) leaves a PENDING
 * row that the reconciliation's {@link #abandonStalePending} sweep revokes after 15 minutes. The secret is in the
 * answer only - never stored, never logged.
 *
 * <p>Without an identity-provider admin client on this instance (no {@code PLATFORM_ADMIN_CLIENT_SECRET}), issue and
 * rotate refuse before writing anything, and revoke still bites at once (platform first), leaving the client disable
 * pending. The scheduled job that calls the two sweeps per tenant is E14 T-203.
 */
public final class SourceCredentialLifecycle implements SourceCredentialAdmin {

    private static final Logger LOG = LoggerFactory.getLogger(SourceCredentialLifecycle.class);

    /** The client id of a credential: derived from its row id, so it is unique per issuer by construction. */
    static final String CLIENT_PREFIX = "src-";

    private final SourceCredentialStore store;
    private final TransactionOperations transactions;
    private final Optional<IdentityProviderAdmin> identityProvider;
    private final Duration rotationGrace;
    private final Duration pendingTimeout;

    public SourceCredentialLifecycle(SourceCredentialStore store, TransactionOperations transactions,
            Optional<IdentityProviderAdmin> identityProvider, Duration rotationGrace, Duration pendingTimeout) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.rotationGrace = Objects.requireNonNull(rotationGrace, "rotationGrace");
        this.pendingTimeout = Objects.requireNonNull(pendingTimeout, "pendingTimeout");
    }

    @Override
    public Issued issue(UUID sourceSystemId, String customerId) {
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(customerId, "customerId");
        IdentityProviderAdmin provider = requireProvider();
        UUID id = UUID.randomUUID();
        String clientId = CLIENT_PREFIX + id;
        transactions.executeWithoutResult(status -> store.insertPending(
                new NewCredential(id, sourceSystemId, customerId, provider.issuer(), clientId)));
        CreatedClient created;
        try {
            created = provider.createConfidentialClient(clientId);
        } catch (IdentityProviderAdminException failed) {
            transactions.executeWithoutResult(status -> {
                store.markRevoked(id);
                store.setIdpDisablePending(id, true);
            });
            LOG.atWarn()
                    .addKeyValue("event", "credential.source_issue_failed")
                    .addKeyValue("credential", id)
                    .addKeyValue("system", sourceSystemId)
                    .log("event=credential.source_issue_failed credential={} system={} - revoked, client disable"
                            + " pending", id, sourceSystemId);
            throw failed;
        }
        Boolean activated = transactions.execute(status -> store.markActive(id));
        if (!Boolean.TRUE.equals(activated)) {
            throw new IllegalStateException("the credential " + id + " was not PENDING when its client was created");
        }
        return new Issued(id, sourceSystemId, customerId, created.clientId(), created.secret());
    }

    @Override
    public Rotated rotate(UUID credentialId) {
        Credential old = transactions.execute(status -> store.find(credentialId)).orElseThrow(
                () -> new CredentialStateException(CredentialStateException.Reason.NOT_FOUND, credentialId));
        if (!old.status().equals("ACTIVE")) {
            throw new CredentialStateException(CredentialStateException.Reason.NOT_ACTIVE, credentialId);
        }
        if (old.revokeAfter() != null) {
            throw new CredentialStateException(CredentialStateException.Reason.ALREADY_ROTATING, credentialId);
        }
        Issued replacement = issue(old.sourceSystemId(), old.customerId());
        Credential scheduled = transactions.execute(status -> {
            if (!store.scheduleRevoke(credentialId, rotationGrace)) {
                throw new CredentialStateException(CredentialStateException.Reason.ALREADY_ROTATING, credentialId);
            }
            return store.find(credentialId).orElseThrow();
        });
        return new Rotated(replacement, credentialId, scheduled.revokeAfter());
    }

    @Override
    public RevokeOutcome revoke(UUID credentialId) {
        Optional<Credential> current = transactions.execute(status -> store.find(credentialId));
        if (current == null || current.isEmpty()) {
            return RevokeOutcome.NOT_FOUND;
        }
        if (current.get().status().equals("REVOKED")) {
            return RevokeOutcome.ALREADY_REVOKED;
        }
        if (identityProvider.isEmpty()) {
            Boolean revoked = transactions.execute(status -> store.markRevoked(credentialId)
                    && store.setIdpDisablePending(credentialId, true));
            if (!Boolean.TRUE.equals(revoked)) {
                return RevokeOutcome.ALREADY_REVOKED;
            }
            LOG.atWarn()
                    .addKeyValue("event", "credential.idp_disable_pending")
                    .addKeyValue("credential", credentialId)
                    .addKeyValue("reason", "identity_provider_not_configured")
                    .log("event=credential.idp_disable_pending credential={} reason=identity_provider_not_configured",
                            credentialId);
            return RevokeOutcome.IDP_DISABLE_PENDING;
        }
        return switch (new SourceCredentialRevocation(store, transactions, identityProvider.get())
                .revoke(credentialId)) {
            case REVOKED -> RevokeOutcome.REVOKED;
            case IDP_DISABLE_PENDING -> RevokeOutcome.IDP_DISABLE_PENDING;
            case NOT_FOUND -> RevokeOutcome.ALREADY_REVOKED; // found above: a concurrent revoke won
        };
    }

    @Override
    public List<Credential> list(UUID sourceSystemId) {
        List<Credential> credentials = transactions.execute(status -> store.findBySourceSystem(sourceSystemId));
        return credentials == null ? List.of() : credentials;
    }

    @Override
    public Optional<Credential> find(UUID credentialId) {
        Optional<Credential> credential = transactions.execute(status -> store.find(credentialId));
        return credential == null ? Optional.empty() : credential;
    }

    /** The reconciliation's sweep for issues that never finished (E14 T-203 schedules it per tenant). */
    public int abandonStalePending() {
        Integer revoked = transactions.execute(status -> store.abandonStalePending(pendingTimeout));
        return revoked == null ? 0 : revoked;
    }

    /** The reconciliation's sweep for rotations whose grace window is over (E14 T-203 schedules it per tenant). */
    public int revokeExpiredRotations() {
        Integer revoked = transactions.execute(status -> store.revokeExpiredRotations());
        return revoked == null ? 0 : revoked;
    }

    private IdentityProviderAdmin requireProvider() {
        return identityProvider.orElseThrow(() -> new IdentityProviderAdminException("Source credential"
                + " administration is not configured on this instance (no identity-provider admin client) - nothing"
                + " was created", null));
    }
}
