package io.integrationplatform.routing.claim;

import io.integrationplatform.delivery.DeliveryClaimService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The stage-2 claim transaction (T-193, ADR-010 section 7): one transaction that takes the ordering-key lock,
 * reads the delivery {@code FOR UPDATE}, evaluates the hold, and sets {@code HELD} (with its event) or
 * {@code SENDING} with a fresh claim token - then commits. The commit is what hands the work over; the caller
 * (the {@code platform.delivery.due} consumer on the partition thread, or the synchronous inline path of
 * ADR-011 Decision 5, which uses exactly this transaction) acknowledges the record with the returned outcome
 * and returns the bulkhead permit on {@link DeliveryClaimService.Outcome#NO_EFFECT}.
 *
 * <p>Camel confinement (R12): this class imports no Camel or Kafka client - it is called from the route.
 * The tenant binding of the caller's thread is the {@code TenantContext} given to the repository behind
 * {@code claims}; the stamp of {@code app.tenant_id} for the transaction is the tenancy job-scheduler wiring
 * of a later phase (the container proof lives in platform-delivery's tests).
 */
public final class DeliveryClaimHandler {

    private final TransactionTemplate transactions;
    private final DeliveryClaimService claims;

    public DeliveryClaimHandler(TransactionTemplate transactions, DeliveryClaimService claims) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    /**
     * Runs the one claim order in one transaction.
     *
     * @param orderingKey the record's ordering key, or {@code null}/{@code blank} for an {@code ordering: NONE}
     *     interface (the advisory lock is skipped)
     * @param deliveryId  the delivery named by the record
     * @param claimToken  the fresh token written with {@code SENDING}
     */
    public DeliveryClaimService.Outcome claim(String orderingKey, UUID deliveryId, UUID claimToken) {
        return transactions.execute(status -> claims.claim(orderingKey, deliveryId, claimToken, Instant.now()));
    }
}
