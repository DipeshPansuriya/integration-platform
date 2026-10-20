package io.integrationplatform.routing.stage1;

import java.time.Duration;
import java.util.UUID;

/**
 * Per-step routing budget seam (T-122 AC-1): stage 1 records the span of each named step of one message here.
 * The production default is {@link #NOOP}; a test or an ops probe binds a bean and the pipeline prices itself.
 */
@FunctionalInterface
public interface RoutingStepRecorder {

    RoutingStepRecorder NOOP = (messageId, step, elapsed) -> {
    };

    void record(UUID messageId, RoutingStep step, Duration elapsed);
}
