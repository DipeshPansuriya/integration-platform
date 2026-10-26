package io.integrationplatform.routing.templates;

import java.util.Objects;
import java.util.UUID;

/**
 * The route-instance cache key of ADR-010 section 3 (amended 2026-10-17, F16): one live instance per
 * (tenant, target endpoint id, endpoint version) triple, so a new endpoint version builds a second instance and the first
 * keeps serving the deliveries pinned to it until it goes idle.
 *
 * @param tenantId         the owning tenant (Karan L-7, defence in depth: the endpoint id is a tenant-owned row UUID
 *                         today; with the tenant in the key a duplicated or shared id can never share a built route)
 * @param targetEndpointId the row's target endpoint
 * @param endpointVersion  the row's {@link RouteConfigRow#endpointVersion()} fingerprint
 */
public record RouteKey(UUID tenantId, UUID targetEndpointId, String endpointVersion) {

    public RouteKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(targetEndpointId, "targetEndpointId");
        Objects.requireNonNull(endpointVersion, "endpointVersion");
    }
}
