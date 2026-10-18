package io.integrationplatform.routing.stage2;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.util.UUID;

/**
 * Where layer 1 hands the exchange over when it ends without success (T-119; ADR-010 section 4: "Camel hands the
 * exchange to our outcome classifier through a {@code direct:} endpoint; Camel's dead-letter channel is never
 * pointed at a queue or topic"). The production implementation is the recorder path - the existing
 * {@code delivery/internal/retry/OutcomeRecorder} over lane C's {@code DeliveryRetryStore} port - and nothing
 * else: the state move (RETRY_WAIT with the backoff, or DEAD_LETTERED with the reason class) stays exactly where
 * it is today, conditional on the claim token (ADR-010 section 7); the claim itself is never touched here.
 */
public interface Stage2OutcomeHandoff {

    /**
     * Hands the exchange of the failed layer-1 series to the outcome path, exactly once per attempt.
     *
     * @param deliveryId                      the delivery whose attempt failed
     * @param claimToken                      the token the claim wrote (the state move stays conditional on it)
     * @param attemptsSoFar                   the delivery's attempt rows so far including this series - advisory
     *                                        only: the attempt budget is counted from the attempt rows since the
     *                                        latest {@code REPLAY} (see {@code SendOutcomes#recordFailure} and
     *                                        {@code JdbcSendOutcomes}), never from this argument
     * @param outcome                         the classified outcome of the last call (never SUCCESS - a successful
     *                                        layer 1 hands off nothing)
     * @param targetDeclaresIdempotentReceive the pinned target's declaration: an AMBIGUOUS outcome retries in
     *                                        layer 2 only for an idempotent target (ADR-010 section 5)
     */
    void handOffAfterLayerOne(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome,
            boolean targetDeclaresIdempotentReceive);
}