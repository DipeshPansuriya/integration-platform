package io.integrationplatform.domain.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollingJobSurfaceRuntimeTest {

    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void pollSinceReturnsChangedTailInsteadOfFullRegister() {
        PollingJobSurface surface = () -> List.of(
                new PollingJobSurface.JobSummary("future", "DELIVERY_RETRY", "SCHEDULED", SINCE.plusSeconds(60), null),
                new PollingJobSurface.JobSummary("recent", "PAYLOAD_PURGE", "SUCCEEDED", null, SINCE.plusSeconds(30)),
                new PollingJobSurface.JobSummary("stale", "DAILY_REPORT", "SUCCEEDED", null, SINCE.minusSeconds(60)),
                new PollingJobSurface.JobSummary("none", "CREDENTIAL_CLEANUP", "PAUSED", null, null));

        assertThat(surface.pollSince(SINCE))
                .extracting(PollingJobSurface.JobSummary::jobId)
                .containsExactlyInAnyOrder("future", "recent");
    }

    @Test
    void nullSinceDegradesToFullPoll() {
        PollingJobSurface surface = () -> List.of(
                new PollingJobSurface.JobSummary("job", "DELIVERY_RETRY", "SCHEDULED", SINCE, null));

        assertThat(surface.pollSince(null)).hasSize(1);
    }
}