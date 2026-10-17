package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * E13 N1 (T110): the HTTP answers of the message tracking reads, without Docker - the service is a mock, so only the
 * controller's mapping is under test: a timeline the bound tenant cannot see is 404 with the config API's
 * {@code {"error": "not found"}} body AND one PATH_ID denied-attempt record (the forged-tenant suite's contract for a
 * path-named foreign row); the actor is resolved before any read. Not tagged container (R03).
 */
class MessageTrackingControllerTest {

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    private final MessageTrackingService service = mock(MessageTrackingService.class);
    private final ConfigActors actors = mock(ConfigActors.class);
    private final TenantDenialRecorder recorder = mock(TenantDenialRecorder.class);
    private final MessageTrackingController controller = controller(recorder);

    private MessageTrackingController controller(TenantDenialRecorder denialRecorder) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", denialRecorder);
        beans.addBean("tenantContext", (TenantContext) () -> Optional.of(TENANT));
        return new MessageTrackingController(service, actors, beans.getBeanProvider(TenantDenialRecorder.class),
                beans.getBeanProvider(TenantContext.class));
    }

    private static MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    @Test
    void aVisibleTimelineIsAnsweredWith200AndTheTimelineBody() {
        UUID trackingId = UUID.randomUUID();
        MessageTrackingService.Timeline timeline = new MessageTrackingService.Timeline(trackingId.toString(),
                UUID.randomUUID().toString(), "ROUTED", "2026-10-16T10:00:00Z", List.of(), null, null, List.of());
        when(service.timeline(trackingId)).thenReturn(Optional.of(timeline));

        ResponseEntity<?> answer = controller.timeline(trackingId,
                get("/api/v1/admin/messages/" + trackingId + "/timeline"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(timeline);
        verify(actors).requireActor();
        verify(recorder, never()).record(any());
    }

    @Test
    void aTimelineTheBoundTenantCannotSeeIs404AndRecordsOnePathIdDenial() {
        UUID foreign = UUID.randomUUID();
        when(service.timeline(foreign)).thenReturn(Optional.empty());
        String path = "/api/v1/admin/messages/" + foreign + "/timeline";

        ResponseEntity<?> answer = controller.timeline(foreign, get(path));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "not found"));
        ArgumentCaptor<DeniedTenantAttempt> recorded = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder).record(recorded.capture());
        assertThat(recorded.getValue().channel()).isEqualTo(DeniedTenantAttempt.Channel.PATH_ID);
        assertThat(recorded.getValue().boundTenantId()).isEqualTo(TENANT);
        assertThat(recorded.getValue().method()).isEqualTo("GET");
        assertThat(recorded.getValue().path()).isEqualTo(path);
    }

    @Test
    void theFailuresListPassesFlowAndDayThroughAndAnswers200() {
        UUID flowId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 16);
        List<MessageTrackingService.Failure> failures = List.of(new MessageTrackingService.Failure(
                UUID.randomUUID().toString(), null, flowId.toString(), null, "ROUTE", "ROUTE_FAILED", null,
                "PLATFORM", "2026-10-16T10:00:00Z"));
        when(service.failures(flowId, day)).thenReturn(failures);

        ResponseEntity<List<MessageTrackingService.Failure>> answer = controller.failures(flowId, day);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(failures);
        verify(actors).requireActor();
    }

    @Test
    void noReadRunsWithoutAResolvedActor() {
        when(actors.requireActor()).thenThrow(new IllegalStateException("NO_BOUND_USER"));

        assertThatThrownBy(() -> controller.timeline(UUID.randomUUID(), get("/api/v1/admin/messages/x/timeline")))
                .hasMessage("NO_BOUND_USER");
        assertThatThrownBy(() -> controller.failures(null, null)).hasMessage("NO_BOUND_USER");
        verifyNoInteractions(service, recorder);
    }
}
