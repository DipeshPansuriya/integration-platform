package io.integrationplatform.domain.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollingJobSurfaceRuntimeTest {

    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * "Changed since" means the job ran since the cutoff: lastRunAt at or after since. A future nextRunAt is the
     * schedule, not a change (every scheduled job has one). State alone never counts as a change either: a paused
     * job counts only through its {@code pausedAt} timestamp (see the pausedAt test below).
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

    /** A paused / disabled job counts when its pausedAt is at or after the cutoff, even if it never ran. */
    @Test
    void pollSinceCountsAJobPausedSinceTheCutoff() {
        PollingJobSurface surface = () -> List.of(
                new PollingJobSurface.JobSummary("paused-after", "A", "PAUSED", null, null, SINCE.plusSeconds(5)),
                new PollingJobSurface.JobSummary("paused-boundary", "B", "PAUSED", null, null, SINCE),
                new PollingJobSurface.JobSummary("paused-before", "C", "PAUSED", null, null, SINCE.minusSeconds(5)),
                new PollingJobSurface.JobSummary("paused-and-ran", "D", "PAUSED", null, SINCE.plusSeconds(1),
                        SINCE.minusSeconds(5)),
                new PollingJobSurface.JobSummary("disabled-no-stamp", "E", "DISABLED", null, null, null));

        assertThat(surface.pollSince(SINCE))
                .extracting(PollingJobSurface.JobSummary::jobId)
                .containsExactlyInAnyOrder("paused-after", "paused-boundary", "paused-and-ran");
    }

    /** The five-argument form stays: no pause timestamp, additive for every existing caller. */
    @Test
    void theFiveArgumentSummaryHasNoPausedAt() {
        assertThat(new PollingJobSurface.JobSummary("j", "T", "ACTIVE", null, null).pausedAt()).isNull();
    }

    @Test
    void nullSinceDegradesToFullPoll() {
        PollingJobSurface surface = () -> List.of(
                new PollingJobSurface.JobSummary("job", "DELIVERY_RETRY", "SCHEDULED", SINCE, null));

        assertThat(surface.pollSince(null)).hasSize(1);
    }
}