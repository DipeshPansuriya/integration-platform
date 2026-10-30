package io.integrationplatform.routing.adapter.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiTargetContractSurfaceTest {

    @Test
    void targetEndpointViewHoldsItsContractFields() {
        Map<Integer, OutcomeClass> dictionary = new LinkedHashMap<>();
        dictionary.put(503, OutcomeClass.TRANSIENT);
        TargetEndpointView view = new TargetEndpointView(
                URI.create("https://example.test/base"),
                "POST",
                1_000,
                "NO_AUTH",
                null, null,
                true,
                dictionary);
        assertNotNull(view.baseUri());
        assertEquals("POST", view.httpMethod());
        assertEquals(1_000, view.timeoutMs());
        assertEquals("NO_AUTH", view.authMethod());
        assertTrue(view.idempotentReceive());
        assertEquals(OutcomeClass.TRANSIENT, view.errorDictionary().get(503));
    }

    @Test
    void apiWireBodyRequiresContentTypeAndBytes() {
        ApiTargetBody.ApiWireBody body = new ApiTargetBody.ApiWireBody("application/json", new byte[]{1, 2});
        assertEquals("application/json", body.contentType());
        assertEquals(2, body.bytes().length);
        assertThrows(NullPointerException.class, () -> new ApiTargetBody.ApiWireBody(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> new ApiTargetBody.ApiWireBody("application/json", null));
    }

    @Test
    void httpSendResponseCarriesStatusBodyAndRetryAfter() {
        HttpSend.Response withRetry = new HttpSend.Response(503, new byte[]{}, null, java.time.Duration.ofSeconds(2));
        assertEquals(503, withRetry.status());
        assertNotNull(withRetry.body());
        assertEquals(java.time.Duration.ofSeconds(2), withRetry.retryAfter());

        HttpSend.Response withoutRetry = new HttpSend.Response(204, new byte[0]);
        assertEquals(204, withoutRetry.status());
        assertFalse(withoutRetry.body().length > 0);
        assertEquals(null, withoutRetry.retryAfter());
    }

    @Test
    void targetEndpointViewsAndApiTargetBodyPortsAreUsable() {
        UUID endpointId = UUID.randomUUID();
        TargetEndpointViews views = id -> Optional.empty();
        assertTrue(views.byEndpointId(endpointId).isEmpty());

        ApiTargetBody bodyPort = send -> Optional.empty();
        Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, endpointId, false);
        assertTrue(bodyPort.bodyFor(send).isEmpty());
    }

    @Test
    void unknownTargetTypeIsRefusedPermanent() {
        Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);
        TargetCall unknown = TargetCall.byType(endpointId -> Optional.of("UNKNOWN"), Map.of("API", s -> {
        }));
        TargetFaultException failure = assertThrows(TargetFaultException.class, () -> unknown.call(send));
        assertEquals(OutcomeClass.PERMANENT, failure.outcomeClass());
    }
}
