package io.integrationplatform.delivery.internal.retry;

import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Turns the classified outcome of a finished delivery attempt into the layer-2 state move (T-090; ADR-010
 * sections 4-6), exclusively through lane C's {@link DeliveryRetryStore} contract, inside the caller's
 * (the attempt executor's) open, tenant-bound transaction:
 *
 * <ul>
 *   <li>{@code SUCCESS} - not this class's move: the classifier completes {@code SENDING -> DELIVERED};</li>
 *   <li>{@code PERMANENT} - straight to {@code DEAD_LETTERED}, never retried (400/401/403/404/409/422,
 *       contract failures: a retry would repeat a certain failure);</li>
 *   <li>{@code AMBIGUOUS} - the request may have been processed: {@code DEAD_LETTERED} with that reason unless
 *       the target declares idempotent receive, in which case it retries like a transient failure (ADR-011);</li>
 *   <li>{@code TRANSIENT} (or the idempotent-ambiguous case) - the next full-jitter backoff step via
 *       {@link RetryPolicy#nextRetryAt}, {@code Retry-After} honoured as the later time; when the attempt count
 *       has reached the policy maximum the delivery dies in {@code DEAD_LETTERED} with the outcome class.</li>
 * </ul>
 *
 * <p>A {@code false} from the store means the claim was already overtaken (stale or foreign token): the store
 * has recorded the {@code LATE_OUTCOME} event and changed nothing, so the recorder accepts it silently -
 * 0 rows is never an exception here (the store contract, ADR-010 section 7).
 */
public final class OutcomeRecorder {

    private final DeliveryRetryStore store;
    private final RetryPolicy policy;
    private final DoubleSupplier jitterSource;

    public OutcomeRecorder(DeliveryRetryStore store, RetryPolicy policy, DoubleSupplier jitterSource) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
    }

    /**
     * Records the outcome of the attempt that just finished under {@code claimToken}.
     *
     * @param attemptsSoFar               attempts recorded for the delivery including this one
     * @param retryAfter                  the target's requested wait, or {@code null} when it sent none
     * @param targetDeclaresIdempotentReceive whether re-sending the same delivery is safe at the target (ADR-011)
     */
    public void record(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome, Instant now,
            Duration retryAfter, boolean targetDeclaresIdempotentReceive) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(now, "now");
        switch (outcome) {
            case SUCCESS -> {
                // the classifier's move (SENDING -> DELIVERED); the recorder does nothing
            }
            case PERMANENT -> store.deadLetter(deliveryId, claimToken, OutcomeClass.PERMANENT);
            case AMBIGUOUS -> {
                if (!targetDeclaresIdempotentReceive) {
                    store.deadLetter(deliveryId, claimToken, OutcomeClass.AMBIGUOUS);
                } else {
                    retryOrDie(deliveryId, claimToken, attemptsSoFar, outcome, now, retryAfter);
                }
            }
            case TRANSIENT -> retryOrDie(deliveryId, claimToken, attemptsSoFar, outcome, now, retryAfter);
        }
    }

    private void retryOrDie(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome,
            Instant now, Duration retryAfter) {
        if (attemptsSoFar >= policy.maxAttempts()) {
            store.deadLetter(deliveryId, claimToken, outcome);
            return;
        }
        Instant next = policy.nextRetryAt(now, attemptsSoFar + 1, retryAfter, jitterSource.getAsDouble());
        store.scheduleRetry(deliveryId, claimToken, next);
    }
}
