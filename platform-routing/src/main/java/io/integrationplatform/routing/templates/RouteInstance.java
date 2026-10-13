package io.integrationplatform.routing.templates;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One live route instance (ADR-010 section 3, T-116): the (target endpoint id, endpoint version) pair's local
 * state on this consuming instance - built from the configuration row's pinned versions, used again by every
 * delivery that needs it, removed after 30 minutes idle. Deliberate local state: rebuilt on demand, never
 * authoritative (ADR-001) - the row's pinned versions frozen at instantiation are what the instance carries, so
 * a later configuration change never alters it (E9-F1-S1 @negative, route side).
 */
public final class RouteInstance {

    private final RouteConfigRow pinned;
    private final Instant builtAt;
    private volatile Instant lastUsedAt;

    RouteInstance(RouteConfigRow pinned, Instant builtAt) {
        this.pinned = Objects.requireNonNull(pinned, "pinned");
        this.builtAt = Objects.requireNonNull(builtAt, "builtAt");
        this.lastUsedAt = builtAt;
    }

    /** The configuration route this instance was built for (its identity in the Camel context's route id). */
    public UUID routeId() {
        return pinned.routeId();
    }

    /** The Camel route id the factory registers for this instance (deterministic per configuration route). */
    public String camelRouteId() {
        return RouteInstanceFactory.ROUTE_ID_PREFIX + pinned.routeId();
    }

    public UUID targetEndpointId() {
        return pinned.targetEndpointId();
    }

    public String targetType() {
        return pinned.targetType();
    }

    public String endpointUri() {
        return pinned.endpointUri();
    }

    /** The contract version pinned when the instance was built - never a later configuration's version. */
    public UUID pinnedContractVersionId() {
        return pinned.contractVersionId();
    }

    /** The mapping version pinned when the instance was built - never a later configuration's version. */
    public UUID pinnedMappingVersionId() {
        return pinned.mappingVersionId();
    }

    Instant builtAt() {
        return builtAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }

    void touch(Instant at) {
        lastUsedAt = at;
    }
}