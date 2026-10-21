package io.integrationplatform.app.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.tracking.api.Report;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcReportReadApiTest {

    @Test
    void reportMapsFacadeReportOntoContractReport() {
        ReportFacade facade = mock(ReportFacade.class);
        JdbcReportReadApi api = new JdbcReportReadApi(facade);
        UUID trackingId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 1, 1);
        MessageTrackingService.Timeline timeline = new MessageTrackingService.Timeline(trackingId.toString(), "flow",
                "DELIVERED", "2026-01-01T00:00:00Z", List.of(), 1000L, null, List.of());
        ReportFacade.Report source = new ReportFacade.Report(trackingId, timeline, List.of(), List.of());
        when(facade.report(trackingId, flowId, day)).thenReturn(source);

        io.integrationplatform.tracking.api.Report result = api.report(trackingId, flowId, day);

        assertThat(result.trackingId()).isEqualTo(trackingId);
        assertThat(result.timeline()).isNotNull();
        assertThat(result.timeline().trackingId()).isEqualTo(trackingId.toString());
    }

    @Test
    void reportWorksWhenBackedByService() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        JdbcReportReadApi api = new JdbcReportReadApi(service);
        UUID trackingId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 1, 1);
        MessageTrackingService.Timeline timeline = new MessageTrackingService.Timeline(trackingId.toString(), "flow",
                "DELIVERED", "2026-01-01T00:00:00Z", List.of(), 1000L, null, List.of());
        when(service.timeline(trackingId)).thenReturn(Optional.of(timeline));
        when(service.failures(flowId, day)).thenReturn(List.of());

        Report result = api.report(trackingId, flowId, day);

        assertThat(result.trackingId()).isEqualTo(trackingId);
        assertThat(result.timeline()).isNotNull();
    }
}
