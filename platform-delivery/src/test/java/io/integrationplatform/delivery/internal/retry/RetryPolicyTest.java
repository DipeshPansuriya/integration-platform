package io.integrationplatform.delivery.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T-090 (E2-F2-S3, S4): the retry timing rules of ADR-010 sections 4 and 6 as pure arithmetic - the layer-2
 * exponential backoff with full jitter and a ceiling, the layer-1 waits behind the 5 s delay cap, and the
 * stale-claim limit (3 x target timeout + layer-1 delay cap + 60 s for clock skew and commit time).
 */
class RetryPolicyTest {

    private static final RetryPolicy DEFAULTS = RetryPolicy.defaults();

    @Test
    void theTeamDefaultsMatchTheAdr010Section4Configuration() {
        assertThat(DEFAULTS.maxAttempts()).isEqualTo(8);
        assertThat(DEFAULTS.baseDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(DEFAULTS.maxDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(DEFAULTS.layerOneDelayCap()).isEqualTo(Duration.ofSeconds(5));
    }

    // ---------- ADR-010 section 6: the stale-claim limit ----------

    @Test
    void staleClaimLimitIsThreeTimeoutsPlusTheLayerOneCapPlusTheSkewAllowance() {
        assertThat(DEFAULTS.staleClaimLimit(Duration.ofSeconds(10)))
                .as("10 s target: 3 x 10 s + 5 s + 60 s").isEqualTo(Duration.ofSeconds(95));
        assertThat(DEFAULTS.staleClaimLimit(Duration.ofSeconds(4)))
                .as("4 s target: 3 x 4 s + 5 s + 60 s").isEqualTo(Duration.ofSeconds(77));
    }

    // ---------- layer 1: three tries, doubling waits, delay time only under the cap ----------

    @Test
    void layerOneWaitsDoubleFromFiveHundredMillisecondsAndTheirSumStaysUnderTheCap() {
        List<Duration> waits = DEFAULTS.layerOneWaits(1.0);

        assertThat(waits).as("first try + at most 2 redeliveries = 3 tries").hasSize(2);
        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(waits.stream().mapToLong(Duration::toMillis).sum())
                .as("the 5 s cap is delay time only - the sum of the waits").isLessThanOrEqualTo(5_000);
    }

    @Test
    void layerOneWaitsShrinkWithJitter() {
        assertThat(DEFAULTS.layerOneWaits(0.5))
                .containsExactly(Duration.ofMillis(250), Duration.ofMillis(500));
    }

    // ---------- layer 2: exponential backoff, full jitter, capped ----------

    @Test
    void theJitteredBackoffGrowsWithTheAttemptAndNeverPassesTheCeiling() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");

        assertThat(DEFAULTS.nextRetryAt(now, 1, null, 1.0)).isEqualTo(now.plusSeconds(30));
        assertThat(DEFAULTS.nextRetryAt(now, 2, null, 1.0)).isEqualTo(now.plusSeconds(60));
        assertThat(DEFAULTS.nextRetryAt(now, 8, null, 1.0))
                .as("8th attempt: 30 s x 2^7 = 6 480 s, capped by the 1 h ceiling")
                .isEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(DEFAULTS.nextRetryAt(now, 8, null, 0.99))
                .as("full jitter can shrink the wait but never extend it past the ceiling")
                .isAfterOrEqualTo(now).isBeforeOrEqualTo(now.plus(Duration.ofHours(1)));
    }

    @Test
    void fullJitterMaySendAsSoonAsNow() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");

        assertThat(DEFAULTS.nextRetryAt(now, 4, null, 0.0)).isEqualTo(now);
    }

    @Test
    void retryAfterWinsWhenItIsLaterButTheCeilingStillCapsIt() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");

        assertThat(DEFAULTS.nextRetryAt(now, 1, Duration.ofSeconds(45), 1.0))
                .as("Retry-After 45 s beats the 30 s backoff").isEqualTo(now.plusSeconds(45));
        assertThat(DEFAULTS.nextRetryAt(now, 1, Duration.ofHours(6), 1.0))
                .as("a huge Retry-After is still capped by the ceiling").isEqualTo(now.plus(Duration.ofHours(1)));
    }

    @Test
    void thePolicyRefusesUnbuildableFigures() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(30), Duration.ofHours(1),
                Duration.ofMillis(500), 2, Duration.ofSeconds(5), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DEFAULTS.nextRetryAt(Instant.now(), 0, null, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
