package io.integrationplatform.delivery.port;

import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * T-199 (ADR-010 section 2): how many bulkhead-deferred deliveries of one target the delivery-retry trigger may
 * move to {@code DUE} in one run - {@code max_concurrent_sends} x the configured instance count, the most the
 * target's bulkheads can take at once. The rest stay in {@code RETRY_WAIT} for the next run, so a backlog of
 * deferred deliveries never stampedes a target that just freed up.
 */
@FunctionalInterface
public interface TargetRedriveCap {

    /** The per-run cap for bulkhead-deferred deliveries of {@code targetEndpointId}; always at least 1. */
    int maxDeferredRedrives(UUID targetEndpointId);

    /** No cap: every due deferred delivery is re-driven (the behaviour before T-199). */
    static TargetRedriveCap unlimited() {
        return target -> Integer.MAX_VALUE;
    }

    /**
     * @param maxConcurrentSends the target's {@code max_concurrent_sends} (ADR-010 section 2: 1-32)
     * @param instanceCount      the configured instance count, at least 1
     */
    static TargetRedriveCap of(ToIntFunction<UUID> maxConcurrentSends, int instanceCount) {
        Objects.requireNonNull(maxConcurrentSends, "maxConcurrentSends");
        if (instanceCount < 1) {
            throw new IllegalArgumentException("instanceCount must be at least 1: " + instanceCount);
        }
        return target -> Math.multiplyExact(maxConcurrentSends.applyAsInt(target), instanceCount);
    }
}
