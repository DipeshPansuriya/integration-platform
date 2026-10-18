package io.integrationplatform.routing.compose;

import io.integrationplatform.routing.templates.RouteInstanceFactory;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The idle removal body of ADR-010 section 3 (amended 2026-10-17, F16): one call removes every route instance idle
 * past the window, so an idle instance leaves even when no delivery arrives. Best-effort and never authoritative: a
 * removed instance is rebuilt lazily.
 *
 * <p>No timer here: ADR-012 section 3 allows no in-memory timer in {@code io.integrationplatform..}
 * (SchedulerTimingRulesTest). ponytail: what triggers {@link #sweepOnce()} on a schedule (a db-scheduler job type
 * needs an ADR-012 register amendment, and route instances are per node) is an open design decision - until then the
 * lazy eviction on use still applies.
 */
public final class RouteIdleSweep {

    private final RouteInstanceFactory factory;
    private final Supplier<Instant> clock;

    public RouteIdleSweep(RouteInstanceFactory factory, Supplier<Instant> clock) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One sweep now. */
    public void sweepOnce() {
        factory.evictIdle(clock.get());
    }
}
