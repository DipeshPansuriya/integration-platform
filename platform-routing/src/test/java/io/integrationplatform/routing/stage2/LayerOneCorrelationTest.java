package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * T-064 (row 278 T107, E12-F1-S1): the journey crosses the Camel route - the deliver stage's send to
 * {@code direct:stage2-send} keeps the correlation id on every try the layer-1 route makes, redeliveries included,
 * so the target call's log lines carry the same id the record brought.
 */
class LayerOneCorrelationTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01";

    private final DefaultCamelContext camel = new DefaultCamelContext();
    private final List<String> idsPerTry = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        camel.stop();
    }

    @Test
    void everyTryOfTheTargetCallCarriesTheJourneysCorrelationId() throws Exception {
        TargetCall twoTransientsThenSuccess = send -> {
            idsPerTry.add(MDC.get(CorrelationContext.CORRELATION_ID));
            if (idsPerTry.size() < 3) {
                throw new TargetFaultException.TransientFaultException("transient try " + idsPerTry.size());
            }
        };
        camel.addRoutes(new LayerOneRouteBuilder(twoTransientsThenSuccess,
                (deliveryId, layer, outcome, startedAt, finishedAt, detail) -> { },
                (deliveryId, claimToken, attemptsSoFar, outcome, idempotent) -> { },
                new LayerOneWaits(2, Duration.ofMillis(1), Duration.ofSeconds(5))));
        camel.start();

        try (CorrelationContext.Scope journey = CorrelationContext.open(TRACEPARENT);
                ProducerTemplate producer = camel.createProducerTemplate()) {
            producer.sendBody(LayerOneRouteBuilder.LAYER_ONE_SEND,
                    new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "key-1", UUID.randomUUID(), false));
        }

        assertThat(idsPerTry).as("the first try and both redeliveries")
                .containsExactly("4bf92f3577b34da6a3ce929d84e974e6", "4bf92f3577b34da6a3ce929d84e974e6",
                        "4bf92f3577b34da6a3ce929d84e974e6");
    }
}
