package io.integrationplatform.app.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.app.report.ReportFacade.Report;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ReportFacadeControllerTest {

    @Test
    void delegatesToTheFacadeAndAnswers200() {
        ReportFacade facade = mock(ReportFacade.class);
        ReportFacadeController controller = new ReportFacadeController(facade);
        UUID trackingId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 16);
        MessageTrackingService.Timeline timeline = new MessageTrackingService.Timeline(trackingId.toString(), flowId.toString(), "ROUTED", "2026-10-16T10:00:00Z", java.util.List.of(), null, null, java.util.List.of());
        Report report = new ReportFacade.Report(trackingId, timeline, java.util.List.of(), java.util.List.of());
        when(facade.report(trackingId, flowId, day)).thenReturn(report);

        ResponseEntity<ReportFacade.Report> answer = controller.report(trackingId, flowId, day);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(report);
    }
}