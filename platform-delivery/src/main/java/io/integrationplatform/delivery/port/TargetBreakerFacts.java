package io.integrationplatform.delivery.port;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * T-183 (E10-F1-S5; ADR-010 section 6 last row, section 8 "circuit breaker"): the delivery-retry job needs the
 * target's breaker state and its per-target trial-call count to cap a half-open re-drive - at most
 * {@code halfOpenTrialCalls} waiting deliveries of that target move to {@code DUE} per pass (the target's
 * {@code half_open_trial_calls} setting, ADR-011 Decision 1 addendum; Resilience4j
 * {@code permittedNumberOfCallsInHalfOpenState}). Every {@code RETRY_WAIT -> DUE} move is this job's (ADR-010 A4),
 * so the cap applies to each pass: a breaker that re-opens before it closes re-drives the trial count again on
 * the next pass instead of leaving the remainder waiting forever.
 *
 * <p>The breaker itself is routing's local, rebuildable state (ADR-010 section 3); this port only reads it. An
 * unknown or absent answer is {@link Optional#empty()} - no cap, today's behaviour - so the trigger never stalls
 * work over a breaker it cannot see.
 */
@FunctionalInterface
public interface TargetBreakerFacts {

    /** The breaker state of one target endpoint, or empty when nothing is known about it. */
    Optional<BreakerFacts> forTarget(UUID targetEndpointId);

    /** {@code unknown}: no target has a known breaker, so no pass is capped (the wiring default). */
    static TargetBreakerFacts unknown() {
        return target -> Optional.empty();
    }

    /**
     * @param state              the breaker's state on the instance answering
     * @param halfOpenTrialCalls the per-target trial-call cap, at least 1
     */
    record BreakerFacts(State state, int halfOpenTrialCalls) {

        public enum State {
            /** The breaker rejects calls; waiting deliveries re-drive at most the trial count per pass. */
            OPEN,
            /** The trial window: at most the trial count is re-driven per pass. */
            HALF_OPEN,
            /** Calls go through: the breaker caps nothing. */
            CLOSED
        }

        public BreakerFacts {
            Objects.requireNonNull(state, "state");
            if (halfOpenTrialCalls < 1) {
                throw new IllegalArgumentException("halfOpenTrialCalls must be at least 1: " + halfOpenTrialCalls);
            }
        }
    }
}
