package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import io.integrationplatform.domain.scheduler.PollingJobSurface;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
        when(runs.search("DELIVERY_RETRY", null, null, null, 1))
                .thenReturn(List.of(new JobRunView(null, "DELIVERY_RETRY", NEXT.minusSeconds(300), LAST, LAST,
                        "instance", "SUCCEEDED", false, null, null)));
        when(runs.search("DAILY_REPORT", null, null, null, 1)).thenReturn(List.of());
        when(runs.search("PAYLOAD_PURGE", null, null, null, 1)).thenReturn(List.of());

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
        JobDefinitionRow active = new JobDefinitionRow("CREDENTIAL_CLEANUP", "CRON", "0 0 * * *", true, false,
                60, 30, "{}");
        when(definitions.all()).thenReturn(List.of(active));
        when(runs.search("CREDENTIAL_CLEANUP", null, null, null, 1)).thenReturn(List.of());
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
        JobDefinitionRow active = new JobDefinitionRow("CREDENTIAL_CLEANUP", "CRON", "0 0 * * *", true, false,
                60, 30, "{}");
        when(definitions.all()).thenReturn(List.of(active));
        when(runs.search("CREDENTIAL_CLEANUP", null, null, null, 1)).thenReturn(List.of());
        when(client.getScheduledExecution(any())).thenThrow(new RuntimeException("boom"));

        List<PollingJobSurface.JobSummary> polled = surface.poll();

        assertThat(polled).singleElement().extracting(PollingJobSurface.JobSummary::nextRunAt).isNull();
    }
}
