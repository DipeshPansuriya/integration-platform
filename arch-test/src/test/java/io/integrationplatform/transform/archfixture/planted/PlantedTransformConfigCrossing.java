package io.integrationplatform.transform.archfixture.planted;

import io.integrationplatform.config.mapping.application.MappingContent;

/**
 * Planted violation for the engine-only direction (architecture.md:51; ADR-006/ADR-013 split): platform-transform
 * executes mappings - platform-config owns them (routes, mappings and immutable mapping versions, the admin API,
 * {@code config/mapping/**} internals). The engine never imports them; it sees mapped versions only through the
 * wiring the caller passes to {@code TransformPort}. Test class only - never in the production scan.
 */
public class PlantedTransformConfigCrossing {

    private MappingContent content; // the plant: config's mapping internals reaching into the engine module

    public MappingContent content() {
        return content;
    }
}