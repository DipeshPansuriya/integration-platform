package io.integrationplatform.domain.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollingJobSurfaceRuntimeTest {

    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * "Changed since" means the job ran since the cutoff: lastRunAt at or after since. A future nextRunAt is the
     * schedule, not a change (every scheduled job has one), and the summary carries no pause / disable timestamp,
     * so state alone never counts as a change.
     */
    @Test
    void pollSinceReturnsOnlyJobsThatRanSinceTheCutoff() {
        PollingJobSurface surface = () -> List.of(
                new PollingJobSurface.JobSummary("future", "DELIVERY_RETRY", "ACTIVE", SINCE.plusSeconds(60), null),
                new PollingJobSurface.JobSummary("recent", "PAYLOAD_PURGE", "ACTIVE", null, SINCE.plusSeconds(30)),
                new PollingJobSurface.JobSummary("boundary", "CLEANUP", "ACTIVE", null, SINCE),
                new PollingJobSurface.JobSummary("stale", "DAILY_REPORT", "ACTIVE", SINCE.plusSeconds(90),
                        SINCE.minusSeconds(60)),
                new PollingJobSurface.JobSummary("none", "CREDENTIAL_CLEANUP", "PAUSED", null, null));

        assertThat(surface.pollSince(SINCE))
                .extracting(PollingJobSurface.JobSummary::jobId)
                .containsExactlyInAnyOrder("recent", "boundary");
    }

    @Test
    void nullSinceDegradesToFullPoll() {
        PollingJobSurface surface = () -> List.of(
                new PollingJobSurface.JobSummary("job", "DELIVERY_RETRY", "SCHEDULED", SINCE, null));

        assertThat(surface.pollSince(null)).hasSize(1);
    }
}