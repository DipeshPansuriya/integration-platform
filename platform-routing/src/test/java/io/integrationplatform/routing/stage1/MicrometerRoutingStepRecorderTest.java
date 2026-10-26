package io.integrationplatform.routing.stage1;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MicrometerRoutingStepRecorderTest {

    @Test
    void aRecordedStepIsExposedAsATimerTaggedByStep() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerRoutingStepRecorder recorder = new MicrometerRoutingStepRecorder(registry);
        UUID messageId = UUID.randomUUID();

        recorder.record(messageId, RoutingStep.EVALUATE_CONDITIONS, Duration.ofMillis(12));

        assertThat(registry.get("ipass.routing.step.duration")
                .tag("step", RoutingStep.EVALUATE_CONDITIONS.name())
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void aNullRegistryFallsBackToTheNoop() {
        assertThat(new MicrometerRoutingStepRecorder(null)).isNotNull();
    }

    @Test
    void aNullOrNegativeInputRecordsNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerRoutingStepRecorder recorder = new MicrometerRoutingStepRecorder(registry);

        recorder.record(UUID.randomUUID(), null, Duration.ofMillis(1));
        recorder.record(UUID.randomUUID(), RoutingStep.EVALUATE_CONDITIONS, null);
        recorder.record(UUID.randomUUID(), RoutingStep.EVALUATE_CONDITIONS, Duration.ofMillis(-1));

        assertThat(registry.getMeters()).as("no timer was even created").isEmpty();
    }

    @Test
    void aNullRegistryRecordsNothingAndDoesNotThrow() {
        new MicrometerRoutingStepRecorder(null).record(UUID.randomUUID(), RoutingStep.EVALUATE_CONDITIONS,
                Duration.ofMillis(5));
    }
}
