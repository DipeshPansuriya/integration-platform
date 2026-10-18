package io.integrationplatform.routing.archfixture.planted;

import io.integrationplatform.config.application.ConfigApiException;

/**
 * Planted violation for {@code RoutingConfigBoundaryRule} (row 278 C2, ADR-017 Decision 6): a routing-scoped class
 * holding config's application-layer {@code ConfigApiException} - the exact shape C2 removed from
 * {@code RouteBuildRefusedException}. One field, so exactly one dependency on config's application layer.
 */
final class PlantedRoutingConfigApplicationUse {

    ConfigApiException refusal;
}
