package io.integrationplatform.app.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that reads a base clock plus an offset which only ever moves forward. <b>Test profile only</b>
 * ({@code e2e}, see {@link PlatformClockConfiguration}): it exists for the E15 end-to-end scenario SC-04
 * ("two weeks later"), never for production time.
 *
 * <p>No HTTP endpoint moves it: an out-of-process run restarts the platform with {@code platform.test-clock.offset}
 * set; an in-process test calls {@link #advance(Duration)} on the bean.
 */
public final class AdjustableClock extends Clock {

    private final Clock base;
    private final AtomicReference<Duration> offset;

    /** A clock reading {@code base} plus {@code offset}; the offset is zero or positive. */
    public AdjustableClock(Clock base, Duration offset) {
        this(base, new AtomicReference<>(forward(offset)));
    }

    private AdjustableClock(Clock base, AtomicReference<Duration> offset) {
        this.base = base;
        this.offset = offset;
    }

    /** Moves this clock forward by {@code amount}; null or negative is refused (time never moves back). */
    public void advance(Duration amount) {
        Duration by = forward(amount);
        offset.accumulateAndGet(by, Duration::plus);
    }

    /** How far this clock is ahead of its base clock. */
    public Duration offset() {
        return offset.get();
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    /** The same timeline (shared offset) seen in another zone. */
    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(base.withZone(zone), offset);
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset.get());
    }

    private static Duration forward(Duration amount) {
        if (amount == null || amount.isNegative()) {
            throw new IllegalArgumentException("the test clock only moves forward, not by " + amount);
        }
        return amount;
    }
}
