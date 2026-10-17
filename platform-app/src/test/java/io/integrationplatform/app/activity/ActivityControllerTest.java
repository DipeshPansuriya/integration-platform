package io.integrationplatform.app.activity;

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
 * Row327 T118 (E12-F2-S2, T-069): the HTTP answers of the tenant activity view, without Docker - the service is a
 * mock, so only the controller's mapping is under test. The list passes the action, the date range and the page
 * through unchanged; an event id the bound tenant cannot see is 404 with the config API's {@code {"error": "not
 * found"}} body AND one PATH_ID denied-attempt record (Gherkin "Another tenant's event id gives nothing"); the actor
 * is resolved before any read. Not tagged container (R03).
 */
class ActivityControllerTest {

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    private final ActivityService service = mock(ActivityService.class);
    private final ConfigActors actors = mock(ConfigActors.class);
    private final TenantDenialRecorder recorder = mock(TenantDenialRecorder.class);
    private final ActivityController controller = controller();

    private ActivityController controller() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", recorder);
        beans.addBean("tenantContext", (TenantContext) () -> Optional.of(TENANT));
        return new ActivityController(service, actors, beans.getBeanProvider(TenantDenialRecorder.class),
                beans.getBeanProvider(TenantContext.class));
    }

    private static ActivityService.Activity activity(long id) {
        return new ActivityService.Activity(Long.toString(id), "admin-a", "CONFIG_CHANGED", List.of("flow:1"),
                null, null, "2026-10-16T10:00:00Z");
    }

    @Test
    void theListPassesActionDateRangeAndPageThroughAndAnswers200() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 16);
        ActivityService.ActivityPage page = new ActivityService.ActivityPage(List.of(activity(7)), 2, 25, false);
        when(service.list("CONFIG_CHANGED", from, to, 2, 25)).thenReturn(page);

        ResponseEntity<ActivityService.ActivityPage> answer = controller.list("CONFIG_CHANGED", from, to, 2, 25);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(page);
        verify(actors).requireActor();
    }

    @Test
    void aVisibleEventIsAnsweredWith200AndTheEventBody() {
        when(service.event(7L)).thenReturn(Optional.of(activity(7)));

        ResponseEntity<?> answer = controller.event(7L, new MockHttpServletRequest("GET", "/api/v1/activity/7"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(activity(7));
        verify(actors).requireActor();
        verify(recorder, never()).record(any());
    }

    @Test
    void anEventTheBoundTenantCannotSeeIs404AndRecordsOnePathIdDenial() {
        when(service.event(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> answer = controller.event(99L, new MockHttpServletRequest("GET", "/api/v1/activity/99"));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "not found"));
        ArgumentCaptor<DeniedTenantAttempt> recorded = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder).record(recorded.capture());
        assertThat(recorded.getValue().channel()).isEqualTo(DeniedTenantAttempt.Channel.PATH_ID);
        assertThat(recorded.getValue().boundTenantId()).isEqualTo(TENANT);
        assertThat(recorded.getValue().method()).isEqualTo("GET");
        assertThat(recorded.getValue().path()).isEqualTo("/api/v1/activity/99");
    }

    @Test
    void noReadRunsWithoutAResolvedActor() {
        when(actors.requireActor()).thenThrow(new IllegalStateException("NO_BOUND_USER"));

        assertThatThrownBy(() -> controller.list(null, null, null, null, null)).hasMessage("NO_BOUND_USER");
        assertThatThrownBy(() -> controller.event(1L, new MockHttpServletRequest("GET", "/api/v1/activity/1")))
                .hasMessage("NO_BOUND_USER");
        verifyNoInteractions(service, recorder);
    }
}
