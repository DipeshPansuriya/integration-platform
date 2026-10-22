package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** E16 consider C2: a circuit deferral never re-drives sooner than 1 s, whatever the breaker's open-state wait says. */
class CircuitOpenRetryAfterTest {

    @Test
    void aZeroOrNegativeWaitIsFlooredToOneSecond() {
        assertThat(new TargetFaultException.CircuitOpenException("open", Duration.ZERO).retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(new TargetFaultException.CircuitOpenException("open", Duration.ofMillis(-5)).retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(new TargetFaultException.CircuitOpenException("open", Duration.ofMillis(200)).retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aLongerWaitIsKept() {
        assertThat(new TargetFaultException.CircuitOpenException("open", Duration.ofSeconds(30)).retryAfter())
                .isEqualTo(Duration.ofSeconds(30));
    }
}
