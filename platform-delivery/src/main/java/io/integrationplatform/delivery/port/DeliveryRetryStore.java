package io.integrationplatform.delivery.port;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The state changes the delivery-retry job and the outcome recorder need, on lane C's delivery tables (E2 seam).
 *
 * <p>Every method runs in the caller's open, tenant-bound transaction (the bound tenant comes from
 * {@code TenantContext}, R08, R11) and never opens its own. Every state change follows ADR-010 section 7: it is a
 * conditional update, and 0 rows means "no effect", reported as {@code false}, never as an exception.
 *
 * <p>Implemented by lane C (T-012, T-013, T-193); called by lane B (T-090). Lane C proves the contract below on the
 * real tables in a container test; lane B tests its callers against a fake that honours it.
 */
public interface DeliveryRetryStore {

    /**
     * Locks up to {@code limit} deliveries in {@code RETRY_WAIT} whose {@code next_attempt_at <= now}, oldest retry
     * time first, skipping rows another transaction holds ({@code FOR UPDATE SKIP LOCKED}) - so two concurrent callers
     * never receive the same delivery.
     */
    List<RetryCandidate> lockDue(Instant now, int limit);

    /**
     * {@code RETRY_WAIT -> DUE} for a delivery locked by {@link #lockDue} in this transaction.
     *
     * @return {@code false} when 0 rows changed (no longer {@code RETRY_WAIT}) - no effect
     */
    boolean markDue(UUID deliveryId);

    /**
     * {@code SENDING -> RETRY_WAIT} with the new retry time, conditional on {@code state = 'SENDING' AND
     * claim_token = claimToken}.
     *
     * @return {@code false} on a stale or foreign token: no state change, and the store records a
     *     {@code LATE_OUTCOME} delivery event (ADR-010 section 6)
     */
    boolean scheduleRetry(UUID deliveryId, UUID claimToken, Instant nextAttemptAt);

    /**
     * {@code SENDING -> DEAD_LETTERED} with the reason class, conditional on {@code state = 'SENDING' AND
     * claim_token = claimToken}; the attempt history stays untouched (append-only).
     *
     * @return {@code false} on a stale or foreign token: no state change, and the store records a
     *     {@code LATE_OUTCOME} delivery event (ADR-010 section 6)
     */
    boolean deadLetter(UUID deliveryId, UUID claimToken, OutcomeClass reasonClass);

    /**
     * E5 T-185 (ADR-007 Decision 7 rule 5; ADR-010 sections 5 and 6): the target credential names a key-encryption-key
     * id this instance does not hold. {@code SENDING -> RETRY_WAIT} conditional on {@code state = 'SENDING' AND
     * claim_token = claimToken}, with a {@code KEY_UNAVAILABLE} delivery event and NO attempt row (no call left the
     * process, no attempt is used up); {@code next_attempt_at} follows the bulkhead-full backoff of ADR-010 section 2
     * (1 s doubling per consecutive key deferral of the delivery, capped at 30 s, full jitter).
     *
     * @param jitter the full-jitter fraction in [0, 1], drawn by the caller
     * @return {@code false} on a stale or foreign token: no state change, a {@code LATE_OUTCOME} event recorded
     */
    default boolean deferKeyUnavailable(UUID deliveryId, UUID claimToken, Instant now, double jitter) {
        throw new UnsupportedOperationException("this delivery store cannot defer a delivery for a missing key");
    }

    /**
     * E5 T-185: {@link #deadLetter(UUID, UUID, OutcomeClass)} with the named reason recorded in the
     * {@code DEAD_LETTER} event (for example "target credential unreadable", ADR-007 Decision 7 rule 5).
     *
     * @return {@code false} on a stale or foreign token: no state change, a {@code LATE_OUTCOME} event recorded
     */
    default boolean deadLetter(UUID deliveryId, UUID claimToken, OutcomeClass reasonClass, String reason) {
        throw new UnsupportedOperationException("this delivery store cannot record a dead-letter reason");
    }
}
