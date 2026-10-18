package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions.Outcome;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions.Result;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * E13 N2 (T111): the HTTP answers of the operator retry and replay, without Docker - the actions are a mock, so only
 * the controller's mapping is under test: the actor is the bound user, a move is 200 with the pinned versions, a
 * delivery the bound tenant cannot see is 404 plus one PATH_ID denial, the wrong state or a missing pin is 409.
 */
class DeliveryOperatorControllerTest {

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID USER = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private final DeliveryOperatorActions actions = mock(DeliveryOperatorActions.class);
    private final ConfigActors actors = mock(ConfigActors.class);
    private final TenantDenialRecorder recorder = mock(TenantDenialRecorder.class);
    private final DeliveryOperatorController controller = controller();

    private DeliveryOperatorController controller() {
        when(actors.requireActor()).thenReturn(
                new LoginBinding(USER, "ACTIVE", TENANT, "T-A", "ACTIVE", ZoneId.of("UTC")));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", recorder);
        beans.addBean("tenantContext", (TenantContext) () -> Optional.of(TENANT));
        return new DeliveryOperatorController(actions, actors, beans.getBeanProvider(TenantDenialRecorder.class),
                beans.getBeanProvider(TenantContext.class));
    }

    private static MockHttpServletRequest post(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    @Test
    void aReplayThatMovesIsAnswered200WithTheResultAndTheBoundUserAsActor() {
        UUID id = UUID.randomUUID();
        Result moved = new Result(Outcome.MOVED_TO_DUE, id, DeliveryState.DUE, UUID.randomUUID(), UUID.randomUUID());
        when(actions.replay(id, USER.toString())).thenReturn(moved);

        ResponseEntity<?> answer = controller.replay(id,
                post("/api/v1/admin/messages/deliveries/" + id + "/replay"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(moved);
        verify(recorder, never()).record(any());
    }

    @Test
    void aRetryThatMovesIsAnswered200() {
        UUID id = UUID.randomUUID();
        Result moved = new Result(Outcome.MOVED_TO_DUE, id, DeliveryState.DUE, UUID.randomUUID(), UUID.randomUUID());
        when(actions.retry(id, USER.toString())).thenReturn(moved);

        ResponseEntity<?> answer = controller.retry(id, post("/api/v1/admin/messages/deliveries/" + id + "/retry"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(moved);
    }

    @Test
    void aDeliveryTheBoundTenantCannotSeeIs404AndRecordsOnePathIdDenial() {
        UUID foreign = UUID.randomUUID();
        when(actions.replay(foreign, USER.toString()))
                .thenReturn(new Result(Outcome.NOT_FOUND, foreign, null, null, null));
        String path = "/api/v1/admin/messages/deliveries/" + foreign + "/replay";

        ResponseEntity<?> answer = controller.replay(foreign, post(path));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "not found"));
        ArgumentCaptor<DeniedTenantAttempt> recorded = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder).record(recorded.capture());
        assertThat(recorded.getValue().channel()).isEqualTo(DeniedTenantAttempt.Channel.PATH_ID);
        assertThat(recorded.getValue().boundTenantId()).isEqualTo(TENANT);
        assertThat(recorded.getValue().method()).isEqualTo("POST");
        assertThat(recorded.getValue().path()).isEqualTo(path);
    }

    @Test
    void theWrongStateIs409AndRecordsNoDenial() {
        UUID id = UUID.randomUUID();
        when(actions.retry(id, USER.toString()))
                .thenReturn(new Result(Outcome.WRONG_STATE, id, DeliveryState.DELIVERED, null, null));

        ResponseEntity<?> answer = controller.retry(id, post("/api/v1/admin/messages/deliveries/" + id + "/retry"));

        assertThat(answer.getStatusCode().value()).isEqualTo(409);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "wrong state", "state", "DELIVERED"));
        verify(recorder, never()).record(any());
    }

    @Test
    void aDeliveryWithoutBothPinsIs409() {
        UUID id = UUID.randomUUID();
        when(actions.replay(id, USER.toString()))
                .thenReturn(new Result(Outcome.NOT_PINNED, id, DeliveryState.DEAD_LETTERED, UUID.randomUUID(), null));

        ResponseEntity<?> answer = controller.replay(id, post("/api/v1/admin/messages/deliveries/" + id + "/replay"));

        assertThat(answer.getStatusCode().value()).isEqualTo(409);
        assertThat(answer.getBody()).isEqualTo(Map.of("error", "not pinned", "state", "DEAD_LETTERED"));
    }

    @Test
    void bothEndpointsAreRuledAsReplayDeadLetter() {
        assertThat(new DeliveryOperatorEndpointRoles().rules()).containsExactlyInAnyOrder(
                new Rule("POST", "/api/v1/admin/messages/deliveries/{deliveryId}/replay", Operation.REPLAY_DEAD_LETTER),
                new Rule("POST", "/api/v1/admin/messages/deliveries/{deliveryId}/retry", Operation.REPLAY_DEAD_LETTER));
    }
}
