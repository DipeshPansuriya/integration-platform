package io.integrationplatform.app.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.app.report.ReportFacade.Report;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ReportFacadeControllerTest {

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    private final ReportFacade facade = mock(ReportFacade.class);
    private final TenantDenialRecorder recorder = mock(TenantDenialRecorder.class);
    private final ReportFacadeController controller = controller(recorder);

    private ReportFacadeController controller(TenantDenialRecorder denialRecorder) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", denialRecorder);
        beans.addBean("tenantContext", (TenantContext) () -> Optional.of(TENANT));
        return new ReportFacadeController(facade, beans.getBeanProvider(TenantDenialRecorder.class),
                beans.getBeanProvider(TenantContext.class));
    }

    private static MockHttpServletRequest get(UUID trackingId) {
        return new MockHttpServletRequest("GET", "/api/v1/reports/facade/" + trackingId);
    }

    @Test
    void delegatesToTheFacadeAndAnswers200() {
        UUID trackingId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 16);
        MessageTrackingService.Timeline timeline = new MessageTrackingService.Timeline(trackingId.toString(), flowId.toString(), "ROUTED", "2026-10-16T10:00:00Z", java.util.List.of(), null, null, java.util.List.of());
        Report report = new ReportFacade.Report(trackingId, timeline, java.util.List.of(), java.util.List.of());
        when(facade.report(trackingId, flowId, day)).thenReturn(report);

        ResponseEntity<ReportFacade.Report> answer = controller.report(trackingId, flowId, day, get(trackingId));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(report);
        verify(recorder, never()).record(any());
    }

    @Test
    void aTrackingIdTheBoundTenantCannotSeeIs404AndRecordsOnePathIdDenial() {
        UUID foreign = UUID.randomUUID();
        when(facade.report(foreign, null, null)).thenReturn(new ReportFacade.Report(foreign, null,
                java.util.List.of(), java.util.List.of()));

        ResponseEntity<ReportFacade.Report> answer = controller.report(foreign, null, null, get(foreign));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody()).isNull();
        ArgumentCaptor<DeniedTenantAttempt> recorded = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder).record(recorded.capture());
        assertThat(recorded.getValue().channel()).isEqualTo(DeniedTenantAttempt.Channel.PATH_ID);
        assertThat(recorded.getValue().boundTenantId()).isEqualTo(TENANT);
        assertThat(recorded.getValue().method()).isEqualTo("GET");
        assertThat(recorded.getValue().path()).isEqualTo("/api/v1/reports/facade/" + foreign);
    }

    @Test
    void aForeignIdAndAnUnknownIdAreIndistinguishableOnTheWire() {
        UUID foreign = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(facade.report(foreign, null, null)).thenReturn(new ReportFacade.Report(foreign, null,
                java.util.List.of(), java.util.List.of()));
        when(facade.report(unknown, null, null)).thenReturn(null);

        ResponseEntity<ReportFacade.Report> a = controller.report(foreign, null, null, get(foreign));
        ResponseEntity<ReportFacade.Report> b = controller.report(unknown, null, null, get(unknown));

        assertThat(a.getStatusCode()).isEqualTo(b.getStatusCode());
        assertThat(a.getBody()).isEqualTo(b.getBody()).isNull();
        assertThat(a.getHeaders()).isEqualTo(b.getHeaders());
    }

    @Test
    void withoutADenialRecorderBeanTheAnswerIsStillThe404() {
        UUID foreign = UUID.randomUUID();
        when(facade.report(foreign, null, null)).thenReturn(null);
        ReportFacadeController bare = new ReportFacadeController(facade,
                new StaticListableBeanFactory().getBeanProvider(TenantDenialRecorder.class),
                new StaticListableBeanFactory().getBeanProvider(TenantContext.class));

        assertThat(bare.report(foreign, null, null, get(foreign)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aProviderFaultSurfacesAsAnErrorAndRecordsNoDenial() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        UUID trackingId = UUID.randomUUID();
        when(service.timeline(trackingId)).thenThrow(new IllegalStateException("db down"));
        ReportFacadeController real = new ReportFacadeController(
                new DefaultReportFacade(new MessageTrackingReportDataProvider(service)),
                beanProvider(TenantDenialRecorder.class, recorder),
                beanProvider(TenantContext.class, (TenantContext) () -> Optional.of(TENANT)));

        assertThatThrownBy(() -> real.report(trackingId, null, null, get(trackingId)))
                .isInstanceOf(IllegalStateException.class);
        verify(recorder, never()).record(any());
    }

    @Test
    void aNullFacadeIsRejectedAtConstruction() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        assertThatThrownBy(() -> new ReportFacadeController(null, beans.getBeanProvider(TenantDenialRecorder.class),
                beans.getBeanProvider(TenantContext.class))).isInstanceOf(NullPointerException.class);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> beanProvider(Class<T> type, T bean) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(type.getSimpleName(), bean);
        return beans.getBeanProvider(type);
    }
}
