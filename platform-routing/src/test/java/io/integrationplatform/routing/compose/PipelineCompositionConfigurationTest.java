package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;

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
}
