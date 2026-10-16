package io.integrationplatform.config.flow.api;

import java.util.Objects;
import java.util.UUID;

/**
 * An interface version went live (T-116, ADR-010 section 3): the configuration module publishes this after the
 * atomic publish wrote the version's routes and mappings over the live rows, and the routing module instantiates
 * the interface's route instances from those rows on the running system - 0 deploys, 0 restarts. Published inside
 * the publish transaction, after every write succeeded; a failed publish announces nothing.
 *
 * @param tenantId    the tenant the interface belongs to
 * @param interfaceId the published interface (flow)
 * @param version     the version number now live
 */
public record InterfacePublished(UUID tenantId, UUID interfaceId, int version) {

    public InterfacePublished {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(interfaceId, "interfaceId");
    }
}
