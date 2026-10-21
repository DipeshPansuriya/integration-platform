package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import io.integrationplatform.domain.scheduler.PollingJobSurface;
import io.integrationplatform.domain.scheduler.PollingJobSurface.JobSummary;
import io.integrationplatform.domain.scheduler.SchedulingBoundary;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * E14 consumer: the JobPollingSurface bean is attached to the scheduling boundary at startup and its summaries are
 * readable through the boundary. ADR-012 sections 4 and 5: a summary carries no payload and no tenant id.
 */
class PollingSchedulingBoundaryTest {

    static final Instant LAST = Instant.parse("2026-10-21T08:55:00Z");

    @Test
    void beforeAnySurfaceIsAttachedTheSummariesAreEmptyNotNull() {
        PollingSchedulingBoundary boundary = new PollingSchedulingBoundary(null);

        assertThat(boundary.summaries()).isEmpty();
        assertThat(boundary.schedulingEnabled()).isTrue();
    }

    @Test
    void attachingASurfaceMakesItsSummariesReadable() {
        PollingSchedulingBoundary boundary = new PollingSchedulingBoundary(null);
        JobSummary summary = new JobSummary("DAILY_REPORT", "DAILY_REPORT", "ACTIVE", null, LAST);

        boundary.attachPollingSurface(() -> List.of(summary));

        assertThat(boundary.summaries()).containsExactly(summary);
    }

    @Test
    void theSpringContextAttachesTheJobPollingSurfaceBeanAtStartup() {
        JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
        JobRunSearch runs = mock(JobRunSearch.class);
        SchedulerClient client = mock(SchedulerClient.class);
        when(definitions.all()).thenReturn(List.of(
                new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "PT5S", true, false, 300, 60, "{}"),
                new JobDefinitionRow("DAILY_REPORT", "CRON", "0 5 0 * * *", true, true, 600, 300, "{}")));
        when(runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT)).thenReturn(List.of(
                new JobRunView(null, "DELIVERY_RETRY", LAST.minusSeconds(60), LAST, LAST, "instance", "SUCCEEDED",
                        false, null, null)));
        when(client.getScheduledExecution(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(JobDefinitionRepository.class, () -> definitions);
            ctx.registerBean(JobRunSearch.class, () -> runs);
            ctx.registerBean(SchedulerClient.class, () -> client);
            ctx.register(JobPollingSurface.class, PollingSchedulingBoundary.class);
            ctx.refresh();

            SchedulingBoundary asBoundary = ctx.getBean(SchedulingBoundary.class);
            assertThat(asBoundary).isSameAs(ctx.getBean(PollingSchedulingBoundary.class));
            assertThat(ctx.getBean(PollingSchedulingBoundary.class).summaries()).containsExactly(
                    new JobSummary("DELIVERY_RETRY", "DELIVERY_RETRY", "ACTIVE", null, LAST),
                    new JobSummary("DAILY_REPORT", "DAILY_REPORT", "PAUSED", null, null));
        }
    }

    @Test
    void aSummaryHasNoPayloadAndNoTenantComponent() {
        assertThat(Arrays.stream(PollingJobSurface.JobSummary.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("jobId", "jobType", "state", "nextRunAt", "lastRunAt");
    }
}
