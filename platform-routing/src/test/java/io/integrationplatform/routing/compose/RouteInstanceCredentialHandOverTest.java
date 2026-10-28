package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.credential.CredentialedDelivery;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.routing.credential.TargetCallCredentialHolder;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2OutcomeHandoff;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Row 540 (PR 40 review, Consider 6): the opened credential must reach the target call even when the route
 * instance changes thread between the dispatch and the layer-1 call. The real dispatch, the real route-instance
 * processor ({@link LayerOneRouteTemplate}) and the real layer-1 route run on a real Camel context; the instance route
 * hops to a worker thread ({@code .threads(1)}). Before the fix the credential rode a ThreadLocal set on the
 * dispatching thread, so the hop lost it and the delivery waited forever.
 */
@Tag("e10-t200")
@ExtendWith(OutputCaptureExtension.class)
class RouteInstanceCredentialHandOverTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final String SECRET = "hand-over-secret-value";

    private final List<String> callThreads = new CopyOnWriteArrayList<>();
    private final List<String> fieldsSeen = new CopyOnWriteArrayList<>();
    private final List<OutcomeClass> handoffs = new CopyOnWriteArrayList<>();
    private DefaultCamelContext camel;

    @AfterEach
    void stop() throws Exception {
        if (camel != null) {
            camel.stop();
        }
        assertThat(TargetCallCredentialHolder.get()).as("the dispatching thread's holder is left clean").isNull();
    }

    @Test
    void theCredentialReachesTheTargetCallAcrossAThreadHop(CapturedOutput output) throws Exception {
        UUID endpoint = UUID.randomUUID(), delivery = UUID.randomUUID(), token = UUID.randomUUID();
        SendOutcomes outcomes = mock(SendOutcomes.class);
        RouteInstanceSendDispatch dispatch = build(endpoint, delivery, token, outcomes, gate(endpoint), true, false);

        dispatch.dispatch(new Stage2Send(delivery, token, "k", endpoint, false));

        assertThat(fieldsSeen).as("the target call saw the opened credential fields")
                .containsExactly("OpenedCredentials[BASIC_PASSWORD]");
        assertThat(callThreads).as("the call ran on the hopped worker thread, not the dispatching one")
                .hasSize(1).doesNotContain(Thread.currentThread().getName());
        verify(outcomes).markDelivered(delivery, token);
        assertThat(handoffs).isEmpty();
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void theCredentialAlsoReachesTheCallOnEveryRedelivery() throws Exception {
        UUID endpoint = UUID.randomUUID(), delivery = UUID.randomUUID(), token = UUID.randomUUID();
        SendOutcomes outcomes = mock(SendOutcomes.class);
        RouteInstanceSendDispatch dispatch = build(endpoint, delivery, token, outcomes, gate(endpoint), true, true);

        dispatch.dispatch(new Stage2Send(delivery, token, "k", endpoint, false));

        assertThat(fieldsSeen).as("first try fails TRANSIENT, the redelivery still holds the credential")
                .containsExactly("OpenedCredentials[BASIC_PASSWORD]", "OpenedCredentials[BASIC_PASSWORD]");
        verify(outcomes).markDelivered(delivery, token);
    }

    @Test
    void withoutAHopTheSameWayStillWorks() throws Exception {
        UUID endpoint = UUID.randomUUID(), delivery = UUID.randomUUID(), token = UUID.randomUUID();
        SendOutcomes outcomes = mock(SendOutcomes.class);
        RouteInstanceSendDispatch dispatch = build(endpoint, delivery, token, outcomes, gate(endpoint), false, false);

        dispatch.dispatch(new Stage2Send(delivery, token, "k", endpoint, false));

        assertThat(fieldsSeen).containsExactly("OpenedCredentials[BASIC_PASSWORD]");
        verify(outcomes).markDelivered(delivery, token);
    }

    @Test
    void aCallWithNoGateStillSeesNoCredential() throws Exception {
        UUID endpoint = UUID.randomUUID(), delivery = UUID.randomUUID(), token = UUID.randomUUID();
        SendOutcomes outcomes = mock(SendOutcomes.class);
        RouteInstanceSendDispatch dispatch = build(endpoint, delivery, token, outcomes, null, true, false);

        dispatch.dispatch(new Stage2Send(delivery, token, "k", endpoint, false));

        assertThat(fieldsSeen).as("slice off: the call sees no credentials (and waits, as before)").isEmpty();
        verify(outcomes, never()).markDelivered(delivery, token);
    }

    // --- harness -----------------------------------------------------------------------------------------------

    private static CredentialedDelivery gate(UUID endpoint) {
        DeliveryRetryStore store = mock(DeliveryRetryStore.class);
        SealedCredential sealed = seal(UUID.randomUUID(), endpoint, SECRET);
        return new CredentialedDelivery(new SendTimeCredentials(target -> List.of(sealed), keys()), id -> { }, store,
                TransactionOperations.withoutTransaction(), () -> 0.5, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RouteInstanceSendDispatch build(UUID endpoint, UUID delivery, UUID token, SendOutcomes outcomes,
            CredentialedDelivery credentialed, boolean hop, boolean failFirstTry) throws Exception {
        AtomicInteger tries = new AtomicInteger();
        Stage2OutcomeHandoff handoff = new Stage2OutcomeHandoff() {
            @Override
            public void handOffAfterLayerOne(UUID d, UUID c, int attemptsSoFar, OutcomeClass outcome,
                    boolean idempotent) {
                handoffs.add(outcome);
            }

            @Override
            public void deferCircuitOpen(UUID d, UUID c, Instant next) {
            }
        };
        camel = new DefaultCamelContext();
        camel.addRoutes(new LayerOneRouteBuilder(send -> {
            // what ApiTargetCall.applyAuth does: no opened credentials -> TRANSIENT, the delivery waits
            var credentials = TargetCallCredentialHolder.get();
            callThreads.add(Thread.currentThread().getName());
            int attempt = tries.incrementAndGet();
            if (credentials == null) {
                throw new TargetFaultException.TransientFaultException("requires credentials; the delivery waits");
            }
            fieldsSeen.add(credentials.toString());
            if (failFirstTry && attempt == 1) {
                throw new TargetFaultException.TransientFaultException("503");
            }
        }, (d, layer, outcome, started, finished, detail) -> { }, handoff,
                new LayerOneWaits(2, Duration.ofMillis(1), Duration.ofSeconds(1))));
        ProducerTemplate producer = camel.createProducerTemplate();
        var processor = new LayerOneRouteTemplate("API", producer)
                .build(new RouteConfigRow(UUID.randomUUID(), endpoint, "API", "https://t.example/a", null, null));
        camel.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                if (hop) {
                    from("direct:instance").threads(1).process(processor);
                } else {
                    from("direct:instance").process(processor);
                }
            }
        });
        camel.start();

        UUID route = UUID.randomUUID(), iface = UUID.randomUUID();
        RouteConfigRow row = new RouteConfigRow(route, endpoint, "API", "https://t.example/a", null, null);
        RouteInstance instance = mock(RouteInstance.class);
        when(instance.consumerUri()).thenReturn("direct:instance");
        RouteInstanceFactory routes = mock(RouteInstanceFactory.class);
        when(routes.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(row)))
                .thenReturn(instance);
        JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
        when(deliveries.claimed(delivery)).thenReturn(Optional.of(
                new JdbcDueDeliveries.DueDelivery(delivery, route, endpoint, iface, "k")));
        TenantContext tenants = mock(TenantContext.class);
        when(tenants.requireTenantId()).thenReturn(UUID.randomUUID());
        return new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(), deliveries, tenants,
                (t, i) -> List.of(row), routes, camel.createProducerTemplate(), outcomes, () -> NOW, credentialed);
    }

    private static SecretKey aesKey() {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) 0x11);
        return new SecretKeySpec(bytes, "AES");
    }

    private static KeyEncryptionKeys keys() {
        return new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.of(aesKey());
            }

            @Override
            public Optional<String> activeId() {
                return Optional.of("K1");
            }
        };
    }

    private static SealedCredential seal(UUID tenant, UUID targetEndpoint, String value) {
        UUID credId = UUID.randomUUID();
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(value.toCharArray(), tenant, credId, targetEndpoint,
                "BASIC_PASSWORD", "K1", aesKey());
        return new SealedCredential(credId, tenant, targetEndpoint, "BASIC_PASSWORD", "K1", sealed.wrappedKey(),
                sealed.ciphertext());
    }
}
