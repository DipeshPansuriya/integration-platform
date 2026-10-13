package io.integrationplatform.config.archfixture.moduleinternal.planted;

import io.integrationplatform.transform.api.TransformPort;
import io.integrationplatform.transform.internal.DashjoinJsonataTransformPort;

/** Planted (never production): a config class that constructs transform's internal engine - the rule must name it. */
public final class PlantedConfigTransformInternalWiring {

    public TransformPort transformPort() {
        return new DashjoinJsonataTransformPort(5_000L);
    }
}
