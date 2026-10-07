package io.integrationplatform.tenancy.internal.credential;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import io.integrationplatform.tenancy.port.SourceCredentialStore.PendingDisable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-186, the credential reconciliation's identity-provider half (ADR-007 Decision 3, S-j): every revoked credential of
 * the BOUND tenant whose client is still {@code idp_disable_pending} is disabled at the identity provider; when the
 * provider confirms, the flag is cleared. A row that fails is retried with exponential backoff and a cap (per
 * credential, kept in this instance's memory) until the provider confirms - the revoke itself already bit at the
 * platform.
 *
 * <p>Called per tenant: the scheduled job that walks the tenants and calls {@link #reconcile()} with each one bound is
 * E14 T-203. A row of another issuer than this provider's is never sent here (the same client id there is a different
 * client); it stays pending.
 */
public final class IdpDisableReconciliation {

    /** One pass: cleared now, tried (or not this provider's) and still pending, and left waiting for their backoff. */
    public record Result(int cleared, int stillPending, int waiting) {
    }

    private record Backoff(int attempts, Instant nextAttempt) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(IdpDisableReconciliation.class);

    private final SourceCredentialStore store;
    private final TransactionOperations transactions;
    private final IdentityProviderAdmin identityProvider;
    private final Clock clock;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Map<UUID, Backoff> backoffs = new ConcurrentHashMap<>();

    public IdpDisableReconciliation(SourceCredentialStore store, TransactionOperations transactions,
            IdentityProviderAdmin identityProvider, Clock clock, Duration initialBackoff, Duration maxBackoff) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
        this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
    }

    public Result reconcile() {
        List<PendingDisable> pending = transactions.execute(status -> store.findIdpDisablePending());
        int cleared = 0;
        int stillPending = 0;
        int waiting = 0;
        for (PendingDisable row : pending == null ? List.<PendingDisable>of() : pending) {
            if (!row.issuer().equals(identityProvider.issuer())) {
                stillPending++;
                continue;
            }
            Instant now = clock.instant();
            Backoff backoff = backoffs.get(row.credentialId());
            if (backoff != null && now.isBefore(backoff.nextAttempt())) {
                waiting++;
                continue;
            }
            try {
                identityProvider.disableClient(row.clientId());
            } catch (IdentityProviderAdmin.IdentityProviderAdminException unavailable) {
                int attempts = backoff == null ? 1 : backoff.attempts() + 1;
                backoffs.put(row.credentialId(),
                        new Backoff(attempts, now.plus(backoff(attempts, initialBackoff, maxBackoff))));
                LOG.atWarn()
                        .addKeyValue("event", "credential.idp_disable_retry")
                        .addKeyValue("credential", row.credentialId())
                        .addKeyValue("attempts", attempts)
                        .log("event=credential.idp_disable_retry credential={} attempts={}", row.credentialId(),
                                attempts);
                stillPending++;
                continue;
            }
            transactions.executeWithoutResult(status -> store.setIdpDisablePending(row.credentialId(), false));
            backoffs.remove(row.credentialId());
            cleared++;
        }
        return new Result(cleared, stillPending, waiting);
    }

    /** {@code initial * 2^(attempts-1)}, capped at {@code max}. */
    static Duration backoff(int attempts, Duration initial, Duration max) {
        if (attempts >= 31) {
            return max;
        }
        Duration wait = initial.multipliedBy(1L << (attempts - 1));
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
