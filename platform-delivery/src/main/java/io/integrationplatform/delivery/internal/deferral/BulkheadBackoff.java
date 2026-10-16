package io.integrationplatform.delivery.internal.deferral;

import java.time.Duration;

/**
 * The bulkhead-full backoff series (T-198, E9-F1-S4, ADR-010 section 2): 1 s for the first deferral, doubling per
 * consecutive deferral, capped at 30 s - 1, 2, 4, 8, 16, 30, 30 ... - with FULL jitter: the wait is the series
 * value scaled by a share in [0, 1] the caller draws (an injected random, so the series is testable as a pure
 * function). A successful claim resets the consecutive count to 0, so the series restarts at 1 s.
 */
public final class BulkheadBackoff {

    /** The cap of the series (ADR-010 section 2). */
    static final long CAP_SECONDS = 30;

    /** 2^5 = 32 is the first doubling past the cap - the shift never needs to go further (no overflow). */
    private static final int LAST_UNCAPPED_SHIFT = 5;

    private BulkheadBackoff() {
    }

    /** The series value in seconds for a delivery that has had {@code consecutiveDeferrals} deferrals so far. */
    public static long seriesSeconds(int consecutiveDeferrals) {
        requireCount(consecutiveDeferrals);
        return Math.min(CAP_SECONDS, 1L << Math.min(consecutiveDeferrals, LAST_UNCAPPED_SHIFT));
    }

    /** The full-jitter wait: the series value scaled by {@code jitter} in [0, 1]. */
    public static Duration waitDuration(int consecutiveDeferrals, double jitter) {
        requireCount(consecutiveDeferrals);
        if (!(jitter >= 0.0 && jitter <= 1.0)) { // also refuses NaN
            throw new IllegalArgumentException("the full-jitter share must be in [0, 1]: jitter = " + jitter);
        }
        return Duration.ofMillis((long) (seriesSeconds(consecutiveDeferrals) * 1_000L * jitter));
    }

    private static void requireCount(int consecutiveDeferrals) {
        if (consecutiveDeferrals < 0) {
            throw new IllegalArgumentException("consecutiveDeferrals cannot be negative: " + consecutiveDeferrals);
        }
    }
}
