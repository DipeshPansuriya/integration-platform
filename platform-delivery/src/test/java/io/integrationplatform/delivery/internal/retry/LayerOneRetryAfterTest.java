package io.integrationplatform.delivery.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-182 (E10-F1-S5; ADR-010 section 4, the layer-1 {@code Retry-After} rule): a redelivery is skipped when the
 * target sent a {@code Retry-After} longer than the layer-1 delay budget - the wait belongs to layer 2, not to a
 * synchronous retry. Layer 1's own delay figures stay the policy's (the cap is delay time only). Not tagged
 * container (R03).
 */
class LayerOneRetryAfterTest {

    private final RetryPolicy policy = RetryPolicy.defaults(); // layer 1: 500 ms doubling, 2 redeliveries, 5 s cap

    @Test
    @DisplayName("No Retry-After, or one inside the budget: the redelivery waits are unchanged")
    void waitsInsideTheBudgetAreUnchanged() {
        assertThat(policy.layerOneWaits(1.0, null)).isEqualTo(policy.layerOneWaits(1.0));
        assertThat(policy.layerOneWaits(1.0, Duration.ofSeconds(1))).isEqualTo(policy.layerOneWaits(1.0));
    }

    @Test
    @DisplayName("Retry-After past the layer-1 delay cap: 0 redeliveries - layer 2 owns the wait")
    void retryAfterPastTheCapSkipsRedelivery() {
        assertThat(policy.layerOneWaits(1.0, Duration.ofSeconds(120))).isEmpty();
        assertThat(policy.layerOneWaits(1.0, Duration.ofSeconds(5))).as("exactly the cap still fits")
                .isEqualTo(policy.layerOneWaits(1.0));
    }
}
