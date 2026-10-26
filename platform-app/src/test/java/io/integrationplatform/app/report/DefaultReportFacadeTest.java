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

class DefaultReportFacadeTest {

    @Test
    void aggregatesTimelineFailuresAndStageTimingsFromTheProvider() {
        ReportDataProvider provider = mock(ReportDataProvider.class);
        UUID trackingId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 16);
        Timeline timeline = new Timeline(trackingId.toString(), flowId.toString(), "ROUTED", "2026-10-16T10:00:00Z", List.of(), null, null, List.of());
        when(provider.loadTimeline(trackingId)).thenReturn(Optional.of(timeline));
        when(provider.loadFailures(flowId, day)).thenReturn(List.of());
        when(provider.loadStageTimings(trackingId)).thenReturn(List.of(new StageTime("INTAKE", "a", "b", 1L, null)));

        ReportFacade.Report report = new DefaultReportFacade(provider).report(trackingId, flowId, day);

        assertThat(report.trackingId()).isEqualTo(trackingId);
        assertThat(report.timeline()).isEqualTo(timeline);
        assertThat(report.failures()).isEmpty();
        assertThat(report.stageTimings()).hasSize(1);
    }

    @Test
    void missingTimelineBecomesNullAndNullListsBecomeEmpty() {
        ReportDataProvider provider = mock(ReportDataProvider.class);
        UUID trackingId = UUID.randomUUID();
        when(provider.loadTimeline(trackingId)).thenReturn(Optional.empty());
        when(provider.loadFailures(null, null)).thenReturn(null);
        when(provider.loadStageTimings(trackingId)).thenReturn(null);

        ReportFacade.Report report = new DefaultReportFacade(provider).report(trackingId, null, null);

        assertThat(report.timeline()).isNull();
        assertThat(report.failures()).isEmpty();
        assertThat(report.stageTimings()).isEmpty();
    }
}