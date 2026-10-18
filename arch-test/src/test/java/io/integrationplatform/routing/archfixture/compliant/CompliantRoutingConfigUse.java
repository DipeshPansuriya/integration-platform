package io.integrationplatform.routing.archfixture.compliant;

import io.integrationplatform.config.flow.api.InterfacePublishRefused;
import io.integrationplatform.config.flow.api.InterfacePublished;
import io.integrationplatform.config.flow.application.FlowBundle;

/**
 * Compliant fixture for {@code RoutingConfigBoundaryRule} (row 278 C2): routing using config's public
 * {@code flow.api} types, plus the one named exception ({@code FlowBundle}, nested type included).
 */
final class CompliantRoutingConfigUse {

    InterfacePublished published;
    InterfacePublishRefused refused;
    FlowBundle bundle;
    FlowBundle.Parsed parsed;
}
