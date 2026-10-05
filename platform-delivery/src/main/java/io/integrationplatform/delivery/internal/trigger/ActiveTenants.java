package io.integrationplatform.delivery.internal.trigger;

import java.util.List;
import java.util.UUID;

/**
 * The active tenants a scanning job loops over (T-090; ADR-011 Decision 8 - ids only, never data). The
 * production implementation is tenancy's {@code @TenantBoundaryCrossing("ipaas_active_tenants")} definer
 * function, which the ingest relay declares through its own seam ({@code ingest.internal.relay
 * .ActiveTenantSource}); the function itself lands with tenancy's persistence slice, so until then this port is
 * the injection point (reported to arjun-fullstack as a seam gap, not papered over).
 */
@FunctionalInterface
public interface ActiveTenants {

    /** The ids of the tenants whose due deliveries this run should look at. */
    List<UUID> activeTenantIds();
}
