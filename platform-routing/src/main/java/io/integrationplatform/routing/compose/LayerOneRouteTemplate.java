package io.integrationplatform.routing.compose;

import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteTemplate;
import java.util.Objects;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;

/**
 * The route template of one target type (ADR-010 section 3, ADR-015; T-049; E18 run 2): the send of every route
 * instance of the type hands the claimed {@code Stage2Send} to the ONE layer-1 route (T-119), whose target call is picked there by
 * the stored {@code system.type} ({@code TargetCall.byType}: API, MQ, KAFKA, DB, FILE) - so the in-attempt redelivery, the attempt rows and the outcome hand-off stay exactly
 * where T-119 put them, and the instance adds only what ADR-010 section 3 gives it: built lazily from the
 * configuration rows on the running system (0 deploys, 0 restarts), its URI scheme guarded when it is built, idle
 * instances removed. Layer 1 runs on its own exchange; a failure it handled (and handed to the outcome path) is copied
 * back as {@code Exchange.EXCEPTION_CAUGHT}; layer 1's explicit outcome flag ({@code LAYER_ONE_SUCCEEDED}) is copied back too, and that
 * flag - not the absence of the caught exception - is how the dispatching stage tells success from a recorded failure.
 * A breaker-open deferral (T191) is copied back the same way: {@code CIRCUIT_DEFERRED} and {@code CIRCUIT_RETRY_AFTER} (T195).
 */
public class LayerOneRouteTemplate implements RouteTemplate {

    private final String targetType;
    private final ProducerTemplate producer;

    public LayerOneRouteTemplate(String targetType, ProducerTemplate producer) {
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    @Override
    public String targetType() {
        return targetType;
    }

    @Override
    public Processor build(RouteConfigRow row) {
        Objects.requireNonNull(row, "row");
        return exchange -> {
            Exchange layerOne = producer.send(LayerOneRouteBuilder.LAYER_ONE_SEND,
                    inner -> {
                        inner.getIn().setBody(exchange.getIn().getBody());
                        // row 540: layer 1 runs on its own exchange - hand the opened secrets over explicitly
                        Object secrets = exchange.getProperty(LayerOneRouteBuilder.TARGET_CALL_SECRETS);
                        if (secrets != null) {
                            inner.setProperty(LayerOneRouteBuilder.TARGET_CALL_SECRETS, secrets);
                        }
                    });
            // a classified failure layer 1 handled (and handed off) - the dispatching stage must not mark success
            Object handled = layerOne.getProperty(Exchange.EXCEPTION_CAUGHT);
            if (handled != null) {
                exchange.setProperty(Exchange.EXCEPTION_CAUGHT, handled);
            }
            // layer 1's explicit final outcome (T188) - the dispatching stage decides DELIVERED on this flag
            Boolean succeeded = layerOne.getProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, Boolean.class);
            if (succeeded != null) {
                exchange.setProperty(LayerOneRouteBuilder.LAYER_ONE_SUCCEEDED, succeeded);
            }
            // T195 (T191-1): layer 1's explicit circuit-defer signal and the wait it handed over - the dispatch reads the flag
            Boolean deferred = layerOne.getProperty(LayerOneRouteBuilder.CIRCUIT_DEFERRED, Boolean.class);
            if (deferred != null) {
                exchange.setProperty(LayerOneRouteBuilder.CIRCUIT_DEFERRED, deferred);
            }
            Object retryAfter = layerOne.getProperty(LayerOneRouteBuilder.CIRCUIT_RETRY_AFTER);
            if (retryAfter != null) {
                exchange.setProperty(LayerOneRouteBuilder.CIRCUIT_RETRY_AFTER, retryAfter);
            }
            if (layerOne.getException() != null) {
                exchange.setException(layerOne.getException());
            }
        };
    }
}
