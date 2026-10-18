package io.integrationplatform.routing.templates;

import java.util.Objects;
import java.util.UUID;

/**
 * The route-instance cache key of ADR-010 section 3 (amended 2026-10-17, F16): one live instance per
 * (target endpoint id, endpoint version) pair, so a new endpoint version builds a second instance and the first
 * keeps serving the deliveries pinned to it until it goes idle.
 *
 * @param targetEndpointId the row's target endpoint
 * @param endpointVersion  the row's {@link RouteConfigRow#endpointVersion()} fingerprint
 */
public record RouteKey(UUID targetEndpointId, String endpointVersion) {

    public RouteKey {
        Objects.requireNonNull(targetEndpointId, "targetEndpointId");
        Objects.requireNonNull(endpointVersion, "endpointVersion");
    }
}
