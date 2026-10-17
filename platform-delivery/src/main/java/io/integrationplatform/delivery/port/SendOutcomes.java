package io.integrationplatform.delivery.port;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.util.UUID;

/**
 * What a stage-2 send leaves in the delivery ledger (T-049 composition; ADR-010 sections 4-7): the attempt rows the
 * layer-1 route writes, the success move and the recorder's move after a failed layer 1. Every method runs in the
 * caller's open, tenant-bound transaction and never opens its own (R08/R11); every state move is conditional on the
 * claim token, so a stale or foreign token changes nothing ("no effect", never an exception).
 */
public interface SendOutcomes {

    /** One call that left the process is exactly one {@code delivery_attempt} row ("the report equals the rows"). */
    void recordAttempt(UUID deliveryId, int layer, OutcomeClass outcome, Instant startedAt, Instant finishedAt,
            String detail);

    /**
     * {@code SENDING -> DELIVERED} under the claim token (the classifier's success move); the held same-key successors
     * the final state unblocks are released in the same transaction (T-165).
     *
     * @return {@code false} when nothing matched (stale or foreign token, or not SENDING)
     */
    boolean markDelivered(UUID deliveryId, UUID claimToken);

    /**
     * Layer 1 ended without success: the recorder's move - {@code RETRY_WAIT} with the backoff while attempts remain,
     * else {@code DEAD_LETTERED} with the reason class; {@code PERMANENT} and a non-idempotent {@code AMBIGUOUS}
     * dead-letter at once (ADR-010 section 5).
     */
    void recordFailure(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome,
            boolean targetDeclaresIdempotentReceive);
}
