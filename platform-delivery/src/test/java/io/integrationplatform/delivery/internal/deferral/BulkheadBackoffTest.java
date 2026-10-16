package io.integrationplatform.delivery.internal.deferral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-198 (E9-F1-S4 "Consecutive bulkhead-full deferrals back off", ADR-010 section 2): the series is 1 s
 * doubling per consecutive deferral, capped at 30 s, with FULL jitter - the effective wait is the series value
 * scaled by a random share in [0, 1]. Positions: 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 and beyond -> 30.
 */
class BulkheadBackoffTest {

    @Test
    @DisplayName("the series at full wait (jitter 1.0) is exactly 1, 2, 4, 8, 16, 30, 30 seconds")
    void seriesDoublingToThirty() {
        assertThat(BulkheadBackoff.seriesSeconds(0)).isEqualTo(1);
        assertThat(BulkheadBackoff.seriesSeconds(1)).isEqualTo(2);
        assertThat(BulkheadBackoff.seriesSeconds(2)).isEqualTo(4);
        assertThat(BulkheadBackoff.seriesSeconds(3)).isEqualTo(8);
        assertThat(BulkheadBackoff.seriesSeconds(4)).isEqualTo(16);
        assertThat(BulkheadBackoff.seriesSeconds(5)).isEqualTo(30);
        assertThat(BulkheadBackoff.seriesSeconds(6)).isEqualTo(30);
        assertThat(BulkheadBackoff.seriesSeconds(100)).isEqualTo(30);
    }

    @Test
    @DisplayName("full jitter scales the series share - and the bounds are enforced")
    void jitterScalesWithinBounds() {
        assertThat(BulkheadBackoff.waitDuration(0, 1.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(BulkheadBackoff.waitDuration(4, 0.5)).isEqualTo(Duration.ofMillis(8_000));
        assertThat(BulkheadBackoff.waitDuration(5, 0.0)).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> BulkheadBackoff.waitDuration(0, -0.01))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jitter");
        assertThatThrownBy(() -> BulkheadBackoff.waitDuration(0, 1.01))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jitter");
        assertThatThrownBy(() -> BulkheadBackoff.waitDuration(-1, 0.5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consecutiveDeferrals");
    }
}
