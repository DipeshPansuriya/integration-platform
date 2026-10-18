package io.integrationplatform.routing.compose;

import io.integrationplatform.routing.templates.RouteInstanceFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduled idle removal of ADR-010 section 3 (amended 2026-10-17, F16): on its own daemon thread, every
 * period, it asks the factory to remove route instances idle past the window - so an idle instance leaves even
 * when no delivery arrives. Best-effort and never authoritative: a removed instance is rebuilt lazily.
 */
public final class RouteIdleSweep {

    private static final Logger log = LoggerFactory.getLogger(RouteIdleSweep.class);

    private final RouteInstanceFactory factory;
    private final Supplier<Instant> clock;
    private final Duration period;
    private ScheduledExecutorService scheduler;

    public RouteIdleSweep(RouteInstanceFactory factory, Supplier<Instant> clock, Duration period) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.period = Objects.requireNonNull(period, "period");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("the sweep period must be positive");
        }
    }

    /** One sweep now. */
    public void sweepOnce() {
        factory.evictIdle(clock.get());
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "route-idle-sweep");
            thread.setDaemon(true);
            return thread;
        });
        long millis = period.toMillis();
        scheduler.scheduleAtFixedRate(this::sweepSafely, millis, millis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    public synchronized boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    private void sweepSafely() {
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // a failed sweep must not cancel the schedule; the next period tries again
            log.warn("route idle sweep failed: {}", e.getClass().getName());
        }
    }
}
