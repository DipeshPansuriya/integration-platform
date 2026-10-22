package io.integrationplatform.routing.compose;

import org.apache.camel.ProducerTemplate;

/**
 * The route template of target type {@code API} (ADR-010 section 3, ADR-015; T-049): the shared layer-1 hand-off
 * ({@link LayerOneRouteTemplate}) whose target call is the API adapter (E10, {@code ApiTargetCall}).
 */
public final class ApiRouteTemplate extends LayerOneRouteTemplate {

    /** The target type of {@code system.type} this template instantiates. */
    public static final String TARGET_TYPE = "API";

    public ApiRouteTemplate(ProducerTemplate producer) {
        super(TARGET_TYPE, producer);
    }
}
