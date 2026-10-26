package io.integrationplatform.routing.stage1;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Runtime slice for the E9 per-step routing budget (T-122): the {@link RoutingStepRecorder} contract backed by a
 * {@link MeterRegistry}, so each named step of stage 1 is published as a timer tagged by step instead of being a
 * compile-time seam only. A {@code null} registry (no metrics wired, or unit tests) records nothing, matching the
 * {@link RoutingStepRecorder#NOOP} contract.
 */
public final class MicrometerRoutingStepRecorder implements RoutingStepRecorder {

    private final MeterRegistry registry;

    public MicrometerRoutingStepRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(UUID messageId, RoutingStep step, Duration elapsed) {
        if (registry == null || step == null || elapsed == null || elapsed.isNegative()) {
            return;
        }
        Timer timer = registry.timer("ipass.routing.step.duration", "step", step.name());
        timer.record(elapsed);
    }
}
