package io.integrationplatform.tenancy.port;

import java.util.List;
import java.util.UUID;

/**
 * The tenants a scanning job loops over (ADR-011 Decision 8: "loop over tenants returned by a definer function
 * {@code ipaas_active_tenants()} (ids only), binding one tenant per transaction"). Ids only - no tenant data crosses
 * the boundary; the caller binds each tenant for its own transaction afterwards. The implementation is tenancy's
 * reviewed crossing (architecture.md section 5, control T5).
 */
@FunctionalInterface
public interface ActiveTenantDirectory {

    /** The ids of the ACTIVE tenants, in a stable order. */
    List<UUID> activeTenantIds();
}
