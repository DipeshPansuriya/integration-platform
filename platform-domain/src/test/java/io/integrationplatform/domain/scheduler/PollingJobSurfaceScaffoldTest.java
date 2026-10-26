package io.integrationplatform.domain.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollingJobSurfaceScaffoldTest {

    @Test
    void pollingSurfaceAndBoundaryCompileAndHoldLowConflictDefaults() {
        PollingJobSurface surface = () -> List.of();
        assertThat(surface.poll()).isEmpty();
        assertThat(surface.pollSince(Instant.now())).isEmpty();

        SchedulingBoundary boundary = new SchedulingBoundary() {
        };
        assertThat(boundary.schedulingEnabled()).isTrue();
        boundary.attachPollingSurface(surface); // no-op, must not throw
    }
}
