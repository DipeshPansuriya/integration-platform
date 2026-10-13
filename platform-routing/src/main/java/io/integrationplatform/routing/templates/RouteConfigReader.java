package io.integrationplatform.routing.templates;

import java.util.List;
import java.util.UUID;

/**
 * The configuration rows a route instance is instantiated from (T-116; ADR-010 section 3): publish reads the
 * active route rows from the running system's configuration, and the consuming instance's first delivery for a
 * (target endpoint id, endpoint version) pair reads them lazily. Lane A1's port: the adapter that reads the real
 * rows is integration wiring (platform-config's published rows - never routing internals, ADR-015); lane A1's
 * tests supply fakes that honour this exact shape.
 */
public interface RouteConfigReader {

    /**
     * @return the target's active route rows for this interface - each row one route instance to instantiate
     */
    List<RouteConfigRow> activeRoutes(UUID tenantId, UUID interfaceId);
}