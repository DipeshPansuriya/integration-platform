package io.integrationplatform.app.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.app.message.MessageTrackingService.StageTime;
import io.integrationplatform.app.message.MessageTrackingService.Timeline;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageTrackingReportDataProviderTest {

    @Test
    void fillsTimelineFailuresAndStageTimingsFromTheService() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        UUID trackingId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 16);
        Timeline timeline = new Timeline(trackingId.toString(), flowId.toString(), "ROUTED",
                "2026-10-16T10:00:00Z", List.of(new StageTime("INTAKE", "a", "b", 1L, null)),
                1L, null, List.of());
        when(service.timeline(trackingId)).thenReturn(Optional.of(timeline));
        when(service.failures(flowId, day)).thenReturn(List.of());

        ReportDataProvider provider = new MessageTrackingReportDataProvider(service);

        assertThat(provider.loadTimeline(trackingId)).contains(timeline);
        assertThat(provider.loadFailures(flowId, day)).isEmpty();
        assertThat(provider.loadStageTimings(trackingId)).hasSize(1);
    }

    @Test
    void missingMessageYieldsEmptyOptionalAndEmptyLists() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        UUID trackingId = UUID.randomUUID();
        when(service.timeline(trackingId)).thenReturn(Optional.empty());
        when(service.failures(null, null)).thenThrow(new IllegalStateException("boom"));

        ReportDataProvider provider = new MessageTrackingReportDataProvider(service);

        assertThat(provider.loadTimeline(trackingId)).isEmpty();
        assertThat(provider.loadFailures(null, null)).isEmpty();
        assertThat(provider.loadStageTimings(trackingId)).isEmpty();
    }
}