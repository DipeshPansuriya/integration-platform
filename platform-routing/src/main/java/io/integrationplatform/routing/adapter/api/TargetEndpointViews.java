package io.integrationplatform.routing.adapter.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The adapter's read port over the configuration module's {@code target_endpoint} rows (E10 narrow, ADR-015:
 * routing reads configuration through a public port - tenant-stamped like every other read, R08).
 */
public interface TargetEndpointViews {

    /** The tenant's endpoint row for {@code endpointId} (the route resolution chose it), or empty when there is none. */
    Optional<TargetEndpointView> byEndpointId(UUID endpointId);
}