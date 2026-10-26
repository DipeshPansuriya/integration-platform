package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T191: a circuit deferral waits for the breaker's REMAINING open time, and only briefly on a half-open refusal. */
class BreakerOpenWaitTest {

    private static final Duration OPEN_WAIT = Duration.ofSeconds(30);

    private static CircuitBreaker watchedBreaker() {
        CircuitBreaker breaker = CircuitBreaker.of("t191-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_WAIT).permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false).build());
        return BreakerOpenWait.watch(breaker);
    }

    private static void trip(CircuitBreaker breaker) {
        for (int i = 0; i < 2; i++) {
            breaker.tryAcquirePermission();
            breaker.onError(1, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("down"));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void anOpenBreakerWaitsForTheRemainingTimeNotTheFullWait() {
        CircuitBreaker breaker = watchedBreaker();
        trip(breaker);

        assertThat(BreakerOpenWait.remaining(breaker, BreakerOpenWait.openedAt(breaker).plusSeconds(20)))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(BreakerOpenWait.remaining(breaker, BreakerOpenWait.openedAt(breaker)))
                .isEqualTo(OPEN_WAIT);
    }

    @Test
    void anOverdueOpenBreakerWaitsZeroAndTheExceptionFloorsItToOneSecond() {
        CircuitBreaker breaker = watchedBreaker();
        trip(breaker);

        Duration overdue = BreakerOpenWait.remaining(breaker, BreakerOpenWait.openedAt(breaker).plusSeconds(45));
        assertThat(overdue).isEqualTo(Duration.ZERO);
        assertThat(new TargetFaultException.CircuitOpenException("open", overdue).retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aHalfOpenRefusalWaitsOnlyBrieflyNotTheOpenStateWait() {
        CircuitBreaker breaker = watchedBreaker();
        trip(breaker);
        breaker.transitionToHalfOpenState();
        assertThat(breaker.tryAcquirePermission()).as("the one trial permit").isTrue();
        assertThat(breaker.tryAcquirePermission()).as("trial cap reached: refused").isFalse();

        assertThat(BreakerOpenWait.remaining(breaker, java.time.Instant.now()))
                .isEqualTo(BreakerOpenWait.HALF_OPEN_WAIT).isLessThan(OPEN_WAIT);
    }

    @Test
    void aBreakerNotWatchedFromTheStartFallsBackToTheFullWait() {
        CircuitBreaker breaker = CircuitBreaker.of("t191-unwatched-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).waitDurationInOpenState(OPEN_WAIT).build());
        breaker.transitionToOpenState();

        assertThat(BreakerOpenWait.remaining(breaker, java.time.Instant.now())).isEqualTo(OPEN_WAIT);
    }

    @Test
    void theRefusalNamesTheRemainingWait() {
        CircuitBreaker breaker = watchedBreaker();
        trip(breaker);

        Duration wait = TargetFaultException.CircuitOpenException.of(breaker, "endpoint").retryAfter();
        assertThat(wait).isLessThanOrEqualTo(OPEN_WAIT).isGreaterThan(OPEN_WAIT.minusSeconds(5));
    }
}
