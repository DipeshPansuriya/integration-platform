package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2AttemptWriter;
import io.integrationplatform.routing.stage2.Stage2OutcomeHandoff;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T188 (from the B32 review): layer 1 states its final outcome explicitly - {@code LAYER_ONE_SUCCEEDED} on the layer-1
 * exchange, copied out by {@link LayerOneRouteTemplate} - and the dispatch marks DELIVERED only on that flag, never on
 * the absence of Camel's {@code EXCEPTION_CAUGHT}. These tests run the REAL layer-1 route through the REAL template
 * processor (the B32 test bypasses the template).
 */
@Tag("e9-t116")
class LayerOneRouteTemplateOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final UUID DELIVERY = seed("delivery"), CLAIM = seed("claim"), ENDPOINT = seed("endpoint"),
            ROUTE = seed("route"), IFACE = seed("iface");

    private final List<String> attemptRows = new ArrayList<>();
    private final List<OutcomeClass> handoffs = new ArrayList<>();
    private DefaultCamelContext camel;

    @AfterEach
    void stop() throws Exception {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    void transientTransientSuccessCopiesBackAnExplicitSuccessFlag() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Exchange outer = runThroughTemplate(send -> {
            if (calls.incrementAndGet() <= 2) {
                throw new TargetFaultException.TransientFaultException("503");
            }
        }, (d, layer, outcome, started, finished, detail) -> attemptRows.add(outcome.name()));

        assertThat(attemptRows).containsExactly("TRANSIENT", "TRANSIENT", "SUCCESS");
        assertThat(outer.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class)).isTrue();
        assertThat(handoffs).as("a success never reaches the hand-off").isEmpty();
    }

    @Test
    void transientExhaustedCopiesBackAFalseFlag() throws Exception {
        Exchange outer = runThroughTemplate(send -> {
            throw new TargetFaultException.TransientFaultException("503");
        }, (d, layer, outcome, started, finished, detail) -> attemptRows.add(outcome.name()));

        assertThat(attemptRows).containsExactly("TRANSIENT", "TRANSIENT", "TRANSIENT");
        assertThat(outer.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class)).isFalse();
        assertThat(handoffs).containsExactly(OutcomeClass.TRANSIENT);
    }

    @Test
    void permanentCopiesBackAFalseFlag() throws Exception {
        Exchange outer = runThroughTemplate(send -> {
            throw new TargetFaultException(OutcomeClass.PERMANENT, "400");
        }, (d, layer, outcome, started, finished, detail) -> attemptRows.add(outcome.name()));

        assertThat(outer.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class)).isFalse();
        assertThat(handoffs).containsExactly(OutcomeClass.PERMANENT);
    }

    @Test
    void ambiguousCopiesBackAFalseFlag() throws Exception {
        Exchange outer = runThroughTemplate(send -> {
            throw new TargetFaultException(OutcomeClass.AMBIGUOUS, "timeout after send");
        }, (d, layer, outcome, started, finished, detail) -> attemptRows.add(outcome.name()));

        assertThat(outer.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class)).isFalse();
        assertThat(handoffs).containsExactly(OutcomeClass.AMBIGUOUS);
    }

    @Test
    void circuitOpenCopiesBackAFalseFlagAndWritesNoAttemptRow() throws Exception {
        Exchange outer = runThroughTemplate(send -> {
            throw new TargetFaultException.CircuitOpenException("open", Duration.ofSeconds(30));
        }, (d, layer, outcome, started, finished, detail) -> attemptRows.add(outcome.name()));

        assertThat(attemptRows).isEmpty();
        assertThat(outer.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class)).isFalse();
    }

    @Test
    void successThenALaterStepThrowsLeavesNoSuccessFlagAndTheDispatchDoesNotMarkDelivered() throws Exception {
        // the call succeeded, but the SUCCESS attempt row cannot be written: the exception ends the layer-1 exchange
        // outside its classified outcomes, so the success must not be claimed
        Stage2AttemptWriter failing = (d, layer, outcome, started, finished, detail) -> {
            throw new IllegalStateException("attempt ledger down");
        };
        SendOutcomes outcomes = mock(SendOutcomes.class);
        Exchange outer = runThroughTemplate(send -> { }, failing);
        assertThat(outer.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class))
                .isNotEqualTo(Boolean.TRUE);
        assertThat(outer.getException()).isNotNull();

        dispatchReturning(outer, outcomes).dispatch(new Stage2Send(DELIVERY, CLAIM, "key-1", ENDPOINT, false));

        verify(outcomes, never()).markDelivered(any(), any());
    }

    @Test
    void anExchangeWithoutTheSuccessFlagIsNeverMarkedDeliveredEvenWithNoCaughtException() {
        SendOutcomes outcomes = mock(SendOutcomes.class);
        Exchange bare = new DefaultExchange(new DefaultCamelContext());

        dispatchReturning(bare, outcomes).dispatch(new Stage2Send(DELIVERY, CLAIM, "key-1", ENDPOINT, false));

        verify(outcomes, never()).markDelivered(any(), any());
    }

    @Test
    void theSuccessFlagWinsOverAStaleCaughtExceptionButAFlaggedExchangeWithAnExceptionIsNotDelivered() {
        SendOutcomes outcomes = mock(SendOutcomes.class);
        Exchange stale = new DefaultExchange(new DefaultCamelContext());
        stale.setProperty(Exchange.EXCEPTION_CAUGHT, new RuntimeException("earlier try"));
        stale.setProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.TRUE);
        dispatchReturning(stale, outcomes).dispatch(new Stage2Send(DELIVERY, CLAIM, "key-1", ENDPOINT, false));
        verify(outcomes).markDelivered(DELIVERY, CLAIM);

        SendOutcomes outcomes2 = mock(SendOutcomes.class);
        Exchange failedAfter = new DefaultExchange(new DefaultCamelContext());
        failedAfter.setProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.TRUE);
        failedAfter.setException(new IllegalStateException("a later step failed"));
        dispatchReturning(failedAfter, outcomes2).dispatch(new Stage2Send(DELIVERY, CLAIM, "key-1", ENDPOINT, false));
        verify(outcomes2, never()).markDelivered(any(), any());
    }

    // --- harness -----------------------------------------------------------------------------------------------

    /** Runs the real layer-1 route and the real template processor; returns the outer (dispatch-side) exchange. */
    private Exchange runThroughTemplate(TargetCall call, Stage2AttemptWriter writer) throws Exception {
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
        camel.addRoutes(new LayerOneRouteBuilder(call, writer, handoff,
                new LayerOneWaits(2, Duration.ofMillis(1), Duration.ofSeconds(1))));
        camel.start();
        ProducerTemplate producer = camel.createProducerTemplate();
        Processor processor = new LayerOneRouteTemplate("API", producer)
                .build(new RouteConfigRow(ROUTE, ENDPOINT, "API", "https://t.example/a", null, null));
        Exchange outer = new DefaultExchange(camel);
        outer.getIn().setBody(new Stage2Send(DELIVERY, CLAIM, "key-1", ENDPOINT, false));
        processor.process(outer);
        return outer;
    }

    /** A dispatch whose producer returns the given exchange as the finished send. */
    private static RouteInstanceSendDispatch dispatchReturning(Exchange done, SendOutcomes outcomes) {
        RouteConfigRow row = new RouteConfigRow(ROUTE, ENDPOINT, "API", "https://t.example/a", null, null);
        RouteInstance instance = mock(RouteInstance.class);
        when(instance.consumerUri()).thenReturn("direct:stage2-send");
        RouteInstanceFactory routes = mock(RouteInstanceFactory.class);
        when(routes.ensure(row)).thenReturn(instance);
        JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
        when(deliveries.claimed(DELIVERY)).thenReturn(Optional.of(
                new JdbcDueDeliveries.DueDelivery(DELIVERY, ROUTE, ENDPOINT, IFACE, "key-1")));
        TenantContext tenants = mock(TenantContext.class);
        when(tenants.requireTenantId()).thenReturn(seed("tenant"));
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(producer.send(eq("direct:stage2-send"), any(Processor.class))).thenReturn(done);
        return new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(), deliveries, tenants,
                (t, i) -> List.of(row), routes, producer, outcomes, () -> NOW);
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
