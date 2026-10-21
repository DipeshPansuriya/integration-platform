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
 * back as {@code Exchange.EXCEPTION_CAUGHT}, which is how the dispatching stage tells success from a recorded failure.
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
                    inner -> inner.getIn().setBody(exchange.getIn().getBody()));
            // a classified failure layer 1 handled (and handed off) - the dispatching stage must not mark success
            Object handled = layerOne.getProperty(Exchange.EXCEPTION_CAUGHT);
            if (handled != null) {
                exchange.setProperty(Exchange.EXCEPTION_CAUGHT, handled);
            }
            if (layerOne.getException() != null) {
                exchange.setException(layerOne.getException());
            }
        };
    }
}
