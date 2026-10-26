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

    private final UUID tenantId;
    private final RouteConfigRow pinned;
    private final Instant builtAt;
    private volatile Instant lastUsedAt;

    RouteInstance(UUID tenantId, RouteConfigRow pinned, Instant builtAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.pinned = Objects.requireNonNull(pinned, "pinned");
        this.builtAt = Objects.requireNonNull(builtAt, "builtAt");
        this.lastUsedAt = builtAt;
    }

    /** The configuration route this instance was built for (its identity in the Camel context's route id). */
    public UUID routeId() {
        return pinned.routeId();
    }

    /** The Camel route id the factory registers for this instance (configuration route plus endpoint version). */
    public String camelRouteId() {
        return RouteInstanceFactory.routeIdOf(pinned);
    }

    /** The (tenant, target endpoint, endpoint version) triple this instance is held under (ADR-010 section 3, F16). */
    public RouteKey key() {
        return pinned.key(tenantId);
    }

    /** The direct consumer this instance answers on; stage 2 sends a delivery here (direct-only dispatch). */
    public String consumerUri() {
        return RouteInstanceFactory.consumerOf(pinned);
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