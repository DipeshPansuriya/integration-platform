package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import io.integrationplatform.domain.scheduler.PollingJobSurface;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JobPollingSurfaceTest {

    static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
    static final Instant NEXT = Instant.parse("2026-10-19T09:05:00Z");
    static final Instant LAST = Instant.parse("2026-10-19T08:55:00Z");

    private final JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
    private final JobRunSearch runs = mock(JobRunSearch.class);
    private final SchedulerClient client = mock(SchedulerClient.class);
    private final JobPollingSurface surface = new JobPollingSurface(definitions, runs, client);

    @Test
    void emptyRegisterPollsEmpty() {
        when(definitions.all()).thenReturn(List.of());

        assertThat(surface.poll()).isEmpty();
    }

    @Test
    void pollMapsDefinitionsToJobSummariesWithoutTenantOrPayload() {
        JobDefinitionRow active = new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "PT5S", true, false,
                300, 60, "{}");
        JobDefinitionRow paused = new JobDefinitionRow("DAILY_REPORT", "CRON", "0 5 0 * * *", true, true,
                600, 300, "{}");
        JobDefinitionRow disabled = new JobDefinitionRow("PAYLOAD_PURGE", "CRON", "0 0 2 * * *", false, false,
                300, 300, "{}");

        when(definitions.all()).thenReturn(List.of(active, paused, disabled));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT))
                .thenReturn(List.of(run("DELIVERY_RETRY", LAST)));

        @SuppressWarnings("unchecked")
        ScheduledExecution<Object> execution = (ScheduledExecution<Object>) mock(ScheduledExecution.class);
        when(execution.getExecutionTime()).thenReturn(NEXT);
        when(client.getScheduledExecution(any())).thenReturn(Optional.of(execution));

        List<PollingJobSurface.JobSummary> polled = surface.poll();

        assertThat(polled).hasSize(3);
        assertThat(polled.get(0)).isEqualTo(new PollingJobSurface.JobSummary(
                "DELIVERY_RETRY", "DELIVERY_RETRY", "ACTIVE", NEXT, LAST));
        assertThat(polled.get(1)).isEqualTo(new PollingJobSurface.JobSummary(
                "DAILY_REPORT", "DAILY_REPORT", "PAUSED", NEXT, null));
        assertThat(polled.get(2)).isEqualTo(new PollingJobSurface.JobSummary(
                "PAYLOAD_PURGE", "PAYLOAD_PURGE", "DISABLED", NEXT, null));
    }

    @Test
    void pollToleratesMissingClientExecution() {
        when(definitions.all()).thenReturn(List.of(row("CREDENTIAL_CLEANUP")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        List<PollingJobSurface.JobSummary> polled = surface.poll();

        assertThat(polled).singleElement().satisfies(summary -> {
            assertThat(summary.jobId()).isEqualTo("CREDENTIAL_CLEANUP");
            assertThat(summary.state()).isEqualTo("ACTIVE");
            assertThat(summary.nextRunAt()).isNull();
            assertThat(summary.lastRunAt()).isNull();
        });
    }

    @Test
    void pollToleratesClientFailure() {
        when(definitions.all()).thenReturn(List.of(row("CREDENTIAL_CLEANUP")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenThrow(new RuntimeException("boom"));

        List<PollingJobSurface.JobSummary> polled = surface.poll();

        assertThat(polled).singleElement().extracting(PollingJobSurface.JobSummary::nextRunAt).isNull();
    }

    @Test
    void pollReadsLatestRunsInOneBatchNotOnePerJob() {
        when(definitions.all()).thenReturn(List.of(row("A"), row("B"), row("C")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(
                List.of(run("A", LAST), run("B", LAST.minusSeconds(60)), run("A", LAST.minusSeconds(900))));
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        List<PollingJobSurface.JobSummary> polled = surface.poll();

        assertThat(polled).extracting(PollingJobSurface.JobSummary::lastRunAt)
                .containsExactly(LAST, LAST.minusSeconds(60), null);
        verify(runs, times(1)).search(any(), any(), any(), any(), any());
    }

    @Test
    void truncatedBatchFallsBackToPerJobLookupOnlyForJobsMissingFromIt() {
        when(definitions.all()).thenReturn(List.of(row("A"), row("OLD")));
        List<JobRunView> full = IntStream.range(0, JobRunSearch.MAX_LIMIT).mapToObj(i -> run("A", LAST)).toList();
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(full);
        when(runs.search("OLD", null, null, null, 1)).thenReturn(List.of(run("OLD", LAST.minusSeconds(86400))));
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        assertThat(surface.poll()).extracting(PollingJobSurface.JobSummary::lastRunAt)
                .containsExactly(LAST, LAST.minusSeconds(86400));
        verify(runs, never()).search(eq("A"), any(), any(), any(), any());
    }

    @Test
    void pollSinceKeepsOnlyJobsThatRanSinceAndSkipsTheClientForTheRest() {
        Instant since = LAST.minusSeconds(30);
        when(definitions.all()).thenReturn(List.of(row("RAN"), row("STALE"), row("NEVER")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT))
                .thenReturn(List.of(run("RAN", LAST), run("STALE", LAST.minusSeconds(600))));
        @SuppressWarnings("unchecked")
        ScheduledExecution<Object> execution = (ScheduledExecution<Object>) mock(ScheduledExecution.class);
        when(execution.getExecutionTime()).thenReturn(NEXT);
        when(client.getScheduledExecution(any())).thenReturn(Optional.of(execution));

        List<PollingJobSurface.JobSummary> changed = surface.pollSince(since);

        assertThat(changed).singleElement().satisfies(summary -> {
            assertThat(summary.jobId()).isEqualTo("RAN");
            assertThat(summary.nextRunAt()).isEqualTo(NEXT);
        });
        verify(client, times(1)).getScheduledExecution(any());
        assertThat(surface.pollSince(null)).hasSize(3);
    }

    /** E14 follow-up: a job paused since the cutoff is a change, though it did not run; pausedAt is reported. */
    @Test
    void pollSinceCountsAPausedJobWhosePausedAtIsAtOrAfterTheCutoff() {
        Instant since = LAST.minusSeconds(30);
        when(definitions.all()).thenReturn(List.of(
                paused("PAUSED_AFTER", since.plusSeconds(10)),
                paused("PAUSED_BOUNDARY", since),
                paused("PAUSED_BEFORE", since.minusSeconds(1)),
                paused("PAUSED_NO_STAMP", null),
                row("QUIET")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        List<PollingJobSurface.JobSummary> changed = surface.pollSince(since);

        assertThat(changed).extracting(PollingJobSurface.JobSummary::jobId)
                .containsExactly("PAUSED_AFTER", "PAUSED_BOUNDARY");
        assertThat(changed).extracting(PollingJobSurface.JobSummary::state).containsOnly("PAUSED");
        assertThat(changed).extracting(PollingJobSurface.JobSummary::pausedAt)
                .containsExactly(since.plusSeconds(10), since);
    }

    /** E14 state-change stamp: a DISABLED-only job and a resumed job are changes, though neither ran nor is paused. */
    @Test
    void pollSinceCountsADisabledOnlyAndAResumedJobByTheirStateChangeStamp() {
        Instant since = LAST.minusSeconds(30);
        when(definitions.all()).thenReturn(List.of(
                stateChanged("DISABLED_AFTER", false, false, since.plusSeconds(10)),
                stateChanged("RESUMED_BOUNDARY", true, false, since),
                stateChanged("DISABLED_BEFORE", false, false, since.minusSeconds(1)),
                stateChanged("NO_STAMP", false, false, null),
                row("QUIET")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        List<PollingJobSurface.JobSummary> changed = surface.pollSince(since);

        assertThat(changed).extracting(PollingJobSurface.JobSummary::jobId)
                .containsExactly("DISABLED_AFTER", "RESUMED_BOUNDARY");
        assertThat(changed).extracting(PollingJobSurface.JobSummary::state).containsExactly("DISABLED", "ACTIVE");
        assertThat(changed).extracting(PollingJobSurface.JobSummary::stateChangedAt)
                .containsExactly(since.plusSeconds(10), since);
    }

    @Test
    void pollReportsStateChangedAtOnTheSummary() {
        Instant at = LAST.minusSeconds(90);
        when(definitions.all()).thenReturn(List.of(stateChanged("DISABLED_JOB", false, false, at), row("PLAIN")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        assertThat(surface.poll()).extracting(PollingJobSurface.JobSummary::stateChangedAt)
                .containsExactly(at, null);
    }

    private static JobDefinitionRow stateChanged(String jobType, boolean enabled, boolean paused, Instant at) {
        return new JobDefinitionRow(jobType, "CRON", "0 0 * * *", enabled, paused, 60, 30, "{}", null, at);
    }

    @Test
    void pollReportsPausedAtOnTheSummaryAndNullWhenNotPaused() {
        Instant at = LAST.minusSeconds(120);
        when(definitions.all()).thenReturn(List.of(paused("PAUSED_JOB", at), row("ACTIVE_JOB")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        assertThat(surface.poll()).extracting(PollingJobSurface.JobSummary::pausedAt)
                .containsExactly(at, null);
    }

    @Test
    void clientFailureIsLoggedAtWarnWithTheJobId() {
        when(definitions.all()).thenReturn(List.of(row("BROKEN_JOB")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenThrow(new RuntimeException("boom"));

        List<ILoggingEvent> events = capture(surface::poll);

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("BROKEN_JOB");
        });
    }

    @Test
    void runLookupFailureIsLoggedAtWarnAndTheJobIsStillListed() {
        when(definitions.all()).thenReturn(List.of(row("BROKEN_JOB")));
        when(runs.search(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(client.getScheduledExecution(any())).thenReturn(Optional.empty());

        List<PollingJobSurface.JobSummary>[] polled = newHolder();
        List<ILoggingEvent> events = capture(() -> polled[0] = surface.poll());

        assertThat(polled[0]).singleElement()
                .extracting(PollingJobSurface.JobSummary::lastRunAt).isNull();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("BROKEN_JOB");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<PollingJobSurface.JobSummary>[] newHolder() {
        return new List[1];
    }

    private static JobRunView run(String jobType, Instant finished) {
        return new JobRunView(null, jobType, finished.minusSeconds(300), finished.minusSeconds(10), finished,
                "instance", "SUCCEEDED", false, null, null);
    }

    private static JobDefinitionRow paused(String jobType, Instant pausedAt) {
        return new JobDefinitionRow(jobType, "CRON", "0 0 * * *", true, true, 60, 30, "{}", pausedAt);
    }

    private static JobDefinitionRow row(String jobType) {
        return new JobDefinitionRow(jobType, "CRON", "0 0 * * *", true, false, 60, 30, "{}");
    }

    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(JobPollingSurface.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
    }
}
