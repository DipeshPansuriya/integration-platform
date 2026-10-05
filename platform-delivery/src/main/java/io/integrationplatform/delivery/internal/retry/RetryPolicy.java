package io.integrationplatform.delivery.internal.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The retry timing rules of ADR-010 section 4, as configuration - not figures scattered through callers (the
 * layer-2 growing exponential backoff with full jitter and a ceiling; the layer-1 doubling waits behind the
 * delay-time-only cap) and section 6's stale-claim limit: a claim older than 3 x the target timeout + the
 * layer-1 delay cap + the clock-skew allowance is stale, because that is the longest legal attempt (three
 * tries each up to the timeout, the waits between them, and time for skew and commit).
 *
 * <p>All timing is pure arithmetic over passed instants (R04: nothing here reads a wall clock). The delivery-retry
 * job (T-090) consults {@link #staleClaimLimit}; the outcome recorder consults {@link #nextRetryAt}; the
 * scheduler-phase replacement of the trigger will reuse the same object.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration layerOneInitialDelay,
        int maxRedeliveries, Duration layerOneDelayCap, Duration clockSkewAllowance) {

    public RetryPolicy {
        if (maxAttempts < 1 || maxRedeliveries < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 and maxRedeliveries >= 0");
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(layerOneInitialDelay, "layerOneInitialDelay");
        Objects.requireNonNull(layerOneDelayCap, "layerOneDelayCap");
        Objects.requireNonNull(clockSkewAllowance, "clockSkewAllowance");
        if (baseDelay.isNegative() || maxDelay.isNegative() || layerOneInitialDelay.isNegative()
                || layerOneDelayCap.isNegative() || clockSkewAllowance.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
        if (baseDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("baseDelay must not exceed maxDelay");
        }
    }

    /** ADR-010 section 4 team defaults: 8 attempts, 30 s base, 1 h ceiling; layer 1: 500 ms doubling, 5 s cap. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(8, Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMillis(500), 2,
                Duration.ofSeconds(5), Duration.ofSeconds(60));
    }

    /** The stale-claim limit of ADR-010 section 6 for a target with this call timeout. */
    public Duration staleClaimLimit(Duration targetTimeout) {
        return targetTimeout.multipliedBy(3).plus(layerOneDelayCap).plus(clockSkewAllowance);
    }

    /**
     * The waits between the layer-1 tries (first send + at most {@code maxRedeliveries} redeliveries), doubling
     * from {@code layerOneInitialDelay} with the given jitter fraction, cut off as soon as a wait would pass the
     * remaining delay-time budget (the cap is delay only - each try's own duration belongs to the target
     * timeout). Executed by Camel's error handler; the figures live here so the cap and the stale limit agree.
     */
    public List<Duration> layerOneWaits(double jitter) {
        requireJitter(jitter);
        List<Duration> waits = new ArrayList<>();
        Duration budgetLeft = layerOneDelayCap;
        for (int redelivery = 0; redelivery < maxRedeliveries; redelivery++) {
            Duration raw = layerOneInitialDelay.multipliedBy(1L << redelivery);
            Duration wait = scaled(raw, jitter);
            if (wait.compareTo(budgetLeft) > 0) {
                break; // would pass the remaining synchronous budget - the redelivery is skipped
            }
            waits.add(wait);
            budgetLeft = budgetLeft.minus(wait);
        }
        return List.copyOf(waits);
    }

    /**
     * When the next layer-2 attempt may run: full-jitter exponential backoff (attempt 1 waits up to the base
     * delay, doubling per attempt, never past {@code maxDelay}); when the target sent {@code Retry-After} it
     * wins if later, still capped by {@code maxDelay} (ADR-010 section 4).
     *
     * @param attempt    the attempt number being scheduled, 1-based
     * @param retryAfter the target's requested wait, or {@code null}
     * @param jitter     the full-jitter fraction in [0, 1] - drawn by the caller from its randomness source
     */
    public Instant nextRetryAt(Instant now, int attempt, Duration retryAfter, double jitter) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
        }
        requireJitter(jitter);
        Objects.requireNonNull(now, "now");
        Duration delay = scaled(exponentialCap(attempt), jitter);
        if (retryAfter != null) {
            Duration cappedRetryAfter = retryAfter.compareTo(maxDelay) > 0 ? maxDelay : retryAfter;
            if (cappedRetryAfter.compareTo(delay) > 0) {
                delay = cappedRetryAfter;
            }
        }
        return now.plus(delay);
    }

    /** base x 2^(attempt-1), saturating at the ceiling without overflowing the shift. */
    private Duration exponentialCap(int attempt) {
        long capNanos = maxDelay.toNanos();
        long nanos = baseDelay.toNanos();
        for (int grown = 1; grown < attempt; grown++) {
            if (nanos > capNanos / 2) {
                return maxDelay;
            }
            nanos *= 2;
        }
        return Duration.ofNanos(Math.min(nanos, capNanos));
    }

    private static Duration scaled(Duration base, double jitter) {
        return Duration.ofNanos((long) (base.toNanos() * jitter));
    }

    private static void requireJitter(double jitter) {
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter fraction must be within [0, 1], was " + jitter);
        }
    }
}
