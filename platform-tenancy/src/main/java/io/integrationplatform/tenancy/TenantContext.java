package io.integrationplatform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant bound to the current unit of work (E1 seam, written by arjun-fullstack before the lanes start).
 *
 * <p>The only source of the bound tenant is the authenticated principal resolved through our own directory (ADR-007
 * Decision 2, ADR-009 Decision 4). Every tenant-scoped read or write - the repository predicate (R08) and the
 * transaction-local {@code app.tenant_id} setting (R11, ADR-007 Decision 6) - takes the tenant from here and from
 * nowhere else.
 *
 * <p>Implemented by lane B (T-001, request principal); used by lane A (T-004, repository predicate) and lane C (T-157,
 * per-transaction stamping). Tests may supply their own implementation.
 */
public interface TenantContext {

    /** The tenant bound for the current unit of work, or empty when none is bound (no identity, or not yet resolved). */
    Optional<UUID> currentTenantId();

    /**
     * The bound tenant; fails closed when none is bound.
     *
     * @throws NoTenantBoundException when no tenant is bound - a tenant-scoped access must never run without one
     */
    default UUID requireTenantId() {
        return currentTenantId().orElseThrow(NoTenantBoundException::new);
    }
}
