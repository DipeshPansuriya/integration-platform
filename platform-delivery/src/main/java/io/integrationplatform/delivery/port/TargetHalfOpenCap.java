package io.integrationplatform.delivery.port;

import java.util.UUID;

/**
 * T-183 (ADR-010 section 6; ADR-011 Decision 1): the target's {@code half_open_trial_calls} (Resilience4j
 * {@code permittedNumberOfCallsInHalfOpenState}, default 3) - how many circuit-deferred deliveries of one target
 * the delivery-retry trigger may move to {@code DUE} in one pass. The rest wait; a breaker that re-opens is
 * tried again with the same count on the next pass.
 */
@FunctionalInterface
public interface TargetHalfOpenCap {

    /** The ADR-011 default trial-call count. */
    int DEFAULT_TRIAL_CALLS = 3;

    /** The per-pass cap for circuit-deferred deliveries of {@code targetEndpointId}; always at least 1. */
    int trialCalls(UUID targetEndpointId);

    /** The ADR-011 default for every target - {@link #DEFAULT_TRIAL_CALLS} per pass - when no per-target setting is known. */
    static TargetHalfOpenCap defaults() {
        return target -> DEFAULT_TRIAL_CALLS;
    }

    /** No cap: every due circuit-deferred delivery is re-driven (the behaviour before T-183). */
    static TargetHalfOpenCap unlimited() {
        return target -> Integer.MAX_VALUE;
    }
}
