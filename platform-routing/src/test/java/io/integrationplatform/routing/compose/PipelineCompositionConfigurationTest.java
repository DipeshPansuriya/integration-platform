package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** T-049 wiring rules that need no container: the scheme list seam fails closed, the partition back-off is capped. */
class PipelineCompositionConfigurationTest {

    @Test
    @DisplayName("the operator's scheme list is read trimmed and lower-cased; unset approves nothing (fail closed)")
    void allowedSchemes() {
        assertThat(PipelineCompositionConfiguration.allowedSchemes(
                new MockEnvironment().withProperty("ROUTING_CORE_ALLOWED_SCHEMES", " HTTP, https ,,")))
                .containsExactlyInAnyOrder("http", "https");
        assertThat(PipelineCompositionConfiguration.allowedSchemes(new MockEnvironment())).isEmpty();
    }

    @Test
    @DisplayName("a paused partition waits 500 ms doubling, never more than 30 s")
    void backoffIsCapped() {
        assertThat(StageConsumerLoop.backoff(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(StageConsumerLoop.backoff(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(StageConsumerLoop.backoff(7)).isEqualTo(Duration.ofSeconds(30));
        assertThat(StageConsumerLoop.backoff(40)).isEqualTo(Duration.ofSeconds(30));
    }

    private static final String MAX_SENDS = "routing.core.stage2.bulkhead.max-concurrent-sends";

    @Test
    @DisplayName("an unset, blank or unreadable bulkhead size falls back to the team default; <= 0 is still refused")
    void stage2BulkheadsFallBackToTeamDefault() {
        PipelineCompositionConfiguration config = new PipelineCompositionConfiguration();
        for (String raw : new String[] {"${TARGET_BULKHEAD_MAX_CONCURRENT_SENDS}", "", "  ", "four"}) {
            assertThatCode(() -> config.stage2Bulkheads(new MockEnvironment().withProperty(MAX_SENDS, raw)).shutdown())
                    .as("raw value [%s]", raw).doesNotThrowAnyException();
        }
        assertThatCode(() -> config.stage2Bulkheads(new MockEnvironment()).shutdown()).doesNotThrowAnyException();
        assertThatThrownBy(() -> config.stage2Bulkheads(new MockEnvironment().withProperty(MAX_SENDS, "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
