package io.integrationplatform.routing.stage2;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T191: how long a delivery refused by a target's breaker should wait. Resilience4j does not expose the time left in
 * the open state, so each breaker is {@link #watch watched} from the moment an adapter first gets it and the instant
 * it last went OPEN is kept. The wait is then the open-state wait minus the time already spent open (never the full
 * configured wait again), and a HALF_OPEN refusal - the trial cap is full while trial calls are in flight - waits only
 * {@link #HALF_OPEN_WAIT}. A breaker that was open before it was watched falls back to the full wait.
 */
public final class BreakerOpenWait {

    /** A half-open refusal clears as soon as the in-flight trial calls finish; a short wait, not the open-state wait. */
    static final Duration HALF_OPEN_WAIT = Duration.ofSeconds(5);

    private static final Map<CircuitBreaker, AtomicReference<Instant>> OPENED_AT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BreakerOpenWait() {
    }

    /** Starts tracking {@code breaker}'s open instant (idempotent) and returns it. */
    public static CircuitBreaker watch(CircuitBreaker breaker) {
        synchronized (OPENED_AT) {
            if (!OPENED_AT.containsKey(breaker)) {
                AtomicReference<Instant> openedAt = new AtomicReference<>();
                OPENED_AT.put(breaker, openedAt);
                breaker.getEventPublisher().onStateTransition(event ->
                        openedAt.set(event.getStateTransition().getToState() == CircuitBreaker.State.OPEN
                                ? Instant.now() : null));
            }
        }
        return breaker;
    }

    /** When the breaker last went OPEN, or null when it is not open or was not watched at that moment. */
    static Instant openedAt(CircuitBreaker breaker) {
        AtomicReference<Instant> openedAt = OPENED_AT.get(breaker);
        return openedAt == null ? null : openedAt.get();
    }

    /** The wait for a call refused by {@code breaker} at {@code now}; zero when the open state is already overdue. */
    static Duration remaining(CircuitBreaker breaker, Instant now) {
        Duration full = Duration.ofMillis(
                breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
        if (breaker.getState() == CircuitBreaker.State.HALF_OPEN) {
            return HALF_OPEN_WAIT;
        }
        Instant openedAt = openedAt(breaker);
        if (breaker.getState() != CircuitBreaker.State.OPEN || openedAt == null) {
            return full;
        }
        Duration left = full.minus(Duration.between(openedAt, now));
        return left.isNegative() ? Duration.ZERO : left;
    }
}
