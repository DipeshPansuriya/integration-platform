package io.integrationplatform.ingest.internal.relay;

import java.util.List;
import java.util.UUID;

/**
 * The list of tenants the outbox relay loops over (ADR-011 Decision 8: scanning jobs "loop over tenants returned
 * by a definer function {@code ipaas_active_tenants()} (ids only), binding one tenant per transaction").
 *
 * <p>The production implementation is a tenant-directory crossing owned by {@code platform-tenancy} (annotated
 * {@code @TenantBoundaryCrossing("ipaas_active_tenants")} inside {@code tenancy.internal}, the established
 * {@code ipaas_login_bootstrap} pattern); it is not part of the frozen E2 seam and is reported to arjun-fullstack.
 * Until it lands, tests and the scheduler drive the relay with a supplied list.
 */
@FunctionalInterface
public interface ActiveTenantSource {

    /** The ids of the tenants whose outbox rows the next cycle should publish. */
    List<UUID> activeTenantIds();
}
