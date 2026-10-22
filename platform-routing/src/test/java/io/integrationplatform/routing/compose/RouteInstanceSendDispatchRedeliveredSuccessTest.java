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
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * B32 (Release 1 SC-03): a delivery whose layer-1 tries answer TRANSIENT, TRANSIENT, SUCCESS inside ONE send must end
 * DELIVERED. Camel leaves {@code EXCEPTION_CAUGHT} on the exchange after a redelivery that later succeeds, so a
 * dispatch that reads that property as "layer 1 failed" never calls {@code markDelivered} and the delivery stays SENDING.
 */
@Tag("e9-t116")
class RouteInstanceSendDispatchRedeliveredSuccessTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Test
    void twoTransientTriesThenSuccessInsideLayerOneMarkTheDeliveryDelivered() throws Exception {
        UUID route = seed("route"), endpoint = seed("endpoint"), delivery = seed("delivery"), claim = seed("claim");
        UUID iface = seed("iface");
        AtomicInteger calls = new AtomicInteger();
        LayerOneRouteBuilder layerOne = new LayerOneRouteBuilder(send -> {
            if (calls.incrementAndGet() <= 2) {
                throw new TargetFaultException.TransientFaultException("503 on try " + calls.get());
            }
        }, (d, layer, outcome, started, finished, detail) -> { },
                new io.integrationplatform.routing.stage2.Stage2OutcomeHandoff() {
                    @Override
                    public void handOffAfterLayerOne(UUID d, UUID c, int attemptsSoFar, OutcomeClass outcome,
                            boolean idempotent) {
                        throw new AssertionError("a success inside layer 1 never reaches the hand-off");
                    }

                    @Override
                    public void deferCircuitOpen(UUID d, UUID c, Instant next) {
                        throw new AssertionError("no circuit involved");
                    }
                }, new LayerOneWaits(2, Duration.ofMillis(1), Duration.ofSeconds(1)));
        DefaultCamelContext camel = new DefaultCamelContext();
        camel.addRoutes(layerOne);
        camel.start();
        try {
            ProducerTemplate real = camel.createProducerTemplate();
            ProducerTemplate producer = mock(ProducerTemplate.class);
            when(producer.send(eq("direct:stage2-send"), any(Processor.class)))
                    .thenAnswer(call -> real.send("direct:stage2-send", call.<Processor>getArgument(1)));
            RouteInstance instance = mock(RouteInstance.class);
            when(instance.consumerUri()).thenReturn("direct:stage2-send");
            RouteConfigRow row = new RouteConfigRow(route, endpoint, "API", "https://t.example/a", null, null);
            RouteInstanceFactory routes = mock(RouteInstanceFactory.class);
            when(routes.ensure(row)).thenReturn(instance);
            JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
            when(deliveries.claimed(delivery)).thenReturn(Optional.of(
                    new JdbcDueDeliveries.DueDelivery(delivery, route, endpoint, iface, "key-1")));
            TenantContext tenants = mock(TenantContext.class);
            when(tenants.requireTenantId()).thenReturn(seed("tenant"));
            SendOutcomes outcomes = mock(SendOutcomes.class);
            RouteInstanceSendDispatch stage2 = new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(),
                    deliveries, tenants, (t, i) -> List.of(row), routes, producer, outcomes, () -> NOW);

            stage2.dispatch(new Stage2Send(delivery, claim, "key-1", endpoint, false));

            assertThat(calls.get()).as("the first try and two redeliveries reached the target").isEqualTo(3);
            verify(outcomes).markDelivered(delivery, claim);
            verify(outcomes, never()).recordFailure(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean());
        } finally {
            camel.stop();
        }
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
