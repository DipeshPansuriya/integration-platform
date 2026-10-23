package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.credential.CredentialedDelivery;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E10-F1-S7 T-200 live at send time: the claimed delivery is opened through the send-time credential gate
 * FIRST - a row whose key is not on this instance costs no attempt (no type dispatch, no attempt row).
 */
@Tag("e10-t200")
@ExtendWith(OutputCaptureExtension.class)
class RouteInstanceSendDispatchCredentialGateTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Random RANDOM = new Random();

    @Test
    void aDeliveryLackingTheTargetKeyIsDeferredWithoutAnyTypeDispatch(CapturedOutput output) {
        UUID route = seed("gate-route");
        UUID endpoint = seed("gate-endpoint");
        UUID delivery = seed("gate-delivery");
        UUID token = UUID.randomUUID();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        DeliveryRetryStore store = mock(DeliveryRetryStore.class);
        when(store.deferKeyUnavailable(delivery, token, NOW, 0.5)).thenReturn(true);
        List<String> reportedMissing = new ArrayList<>();
        SealedCredential sealed = seal(UUID.randomUUID(), endpoint, "sealed-once", "K1");
        CredentialedDelivery credentialed = new CredentialedDelivery(
                new SendTimeCredentials(target -> List.of(sealed), keys(Map.of("K2", aesKey()))),
                reportedMissing::add, store, TransactionOperations.withoutTransaction(), () -> 0.5,
                Clock.fixed(NOW, ZoneOffset.UTC), meters);

        RouteInstanceSendDispatch stage2 = dispatcher(route, endpoint, delivery, token, store, credentialed, meters,
                producerNeverCalled(), mock(SendOutcomes.class));

        stage2.dispatch(new Stage2Send(delivery, token, "gate-key", endpoint, false));

        verify(producerNeverCalled(), never()).send(anyString(), any(Processor.class));
        verify(store).deferKeyUnavailable(delivery, token, NOW, 0.5);
        verifyNoMoreInteractions(store);
        assertThat(meters.counter("platform.delivery.key_unavailable").count()).as("the alert is priced")
                .isEqualTo(1.0);
        assertThat(reportedMissing).as("readiness is told (A3)").containsExactly("K1");
        assertThat(output.getAll()).contains("credential.key_unavailable");
    }

    @Test
    void aDeliveryHoldingTheTargetKeyFlowsToTheTypeDispatchUnchanged() {
        UUID route = seed("gated-route");
        UUID endpoint = seed("gated-endpoint");
        UUID delivery = seed("gated-delivery");
        UUID token = UUID.randomUUID();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        DeliveryRetryStore store = mock(DeliveryRetryStore.class);
        List<String> reportedMissing = new ArrayList<>();
        SealedCredential sealed = seal(UUID.randomUUID(), endpoint, "opened-fine", "K2");
        CredentialedDelivery credentialed = new CredentialedDelivery(
                new SendTimeCredentials(target -> List.of(sealed), keys(Map.of("K2", aesKey()))),
                reportedMissing::add, store, TransactionOperations.withoutTransaction(), () -> 0.5,
                Clock.fixed(NOW, ZoneOffset.UTC), meters);

        Exchange done = mock(Exchange.class);
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(done.getProperty(io.integrationplatform.routing.stage2.LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class))
                .thenReturn(Boolean.TRUE); // layer 1's explicit outcome (T188)
        when(producer.send(anyString(), any(Processor.class))).thenReturn(done);
        SendOutcomes outcomes = mock(SendOutcomes.class);
        RouteInstanceSendDispatch stage2 = dispatcher(route, endpoint, delivery, token, store, credentialed, meters,
                producer, outcomes);

        stage2.dispatch(new Stage2Send(delivery, token, "gate-key", endpoint, false));

        verify(producer).send(eq("direct:gate"), any(Processor.class));
        verify(outcomes).markDelivered(delivery, token);
        verifyNoMoreInteractions(store);
        assertThat(meters.counter("platform.delivery.key_unavailable").count()).isZero();
    }

    private RouteInstanceSendDispatch dispatcher(UUID route, UUID endpoint, UUID delivery, UUID token,
            DeliveryRetryStore store, CredentialedDelivery credentialed,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry meters, ProducerTemplate producer,
            SendOutcomes outcomes) {
        JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
        when(deliveries.claimed(delivery)).thenReturn(Optional.of(
                new JdbcDueDeliveries.DueDelivery(delivery, route, endpoint, seed("gate-iface"), "gate-key")));
        TenantContext tenants = mock(TenantContext.class);
        when(tenants.requireTenantId()).thenReturn(seed("gate-tenant"));
        RouteConfigRow row = new RouteConfigRow(route, endpoint, "API", "https://gate.example/import", null, null);
        RouteInstance instance = mock(RouteInstance.class);
        when(instance.consumerUri()).thenReturn("direct:gate");
        RouteInstanceFactory routes = mock(RouteInstanceFactory.class);
        when(routes.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(row))).thenReturn(instance);
        return new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(), deliveries, tenants,
                (t, i) -> List.of(row), routes, producer, outcomes, () -> NOW, credentialed);
    }

    private static ProducerTemplate producerNeverCalled() {
        return mock(ProducerTemplate.class);
    }

    private UUID seed(String label) {
        return UUID.randomUUID();
    }

    private static SecretKey aesKey() {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) 0x11);
        return new SecretKeySpec(bytes, "AES");
    }

    private KeyEncryptionKeys keys(Map<String, SecretKey> byId) {
        return new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.ofNullable(byId.get(kekId));
            }
            @Override
            public Optional<String> activeId() {
                return Optional.of("K2");
            }
        };
    }

    private SealedCredential seal(UUID targetEndpoint, String value, String kekId) {
        return seal(UUID.randomUUID(), targetEndpoint, value, kekId);
    }

    private SealedCredential seal(UUID tenant, UUID targetEndpoint, String value, String kekId) {
        UUID credId = UUID.randomUUID();
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(value.toCharArray(), tenant, credId, targetEndpoint,
                "BASIC_PASSWORD", kekId, aesKey());
        return new SealedCredential(credId, tenant, targetEndpoint, "BASIC_PASSWORD", kekId, sealed.wrappedKey(),
                sealed.ciphertext());
    }
}