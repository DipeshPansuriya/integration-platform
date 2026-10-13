package io.integrationplatform.routing.stage2;

import java.time.Duration;
import java.util.Objects;

/**
 * The layer-1 wait figures (T-119; ADR-010 section 4): at most 2 redeliveries, 500 ms initial delay, doubling,
 * with jitter - and the 5 s cap is DELAY TIME ONLY (each try's own duration is the target's timeout, not this
 * cap; the stale-claim limit of section 6 counts three tries at the timeout plus this cap, so a shorter set of
 * waits can never push a legal attempt past it). The figures live here once, so stage 2's Camel error handler and
 * the layer-2 figures cannot drift apart; the same numbers' home in the retry configuration is
 * {@code RetryPolicy}'s layer-1 fields (platform-delivery) - wiring binds them to THIS record, never a second
 * set of figures.
 *
 * @param maxRedeliveries at most 2 (ADR-010 section 4)
 * @param initialDelay    500 ms at the team default
 * @param delayCap        5 s - the sum of the waits between tries never passes it
 */
public record LayerOneWaits(int maxRedeliveries, Duration initialDelay, Duration delayCap) {

    public LayerOneWaits {
        if (maxRedeliveries < 0) {
            throw new IllegalArgumentException("maxRedeliveries must be >= 0");
        }
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(delayCap, "delayCap");
        if (initialDelay.isNegative() || delayCap.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
    }

    public static LayerOneWaits defaults() {
        return new LayerOneWaits(2, Duration.ofMillis(500), Duration.ofSeconds(5));
    }

}