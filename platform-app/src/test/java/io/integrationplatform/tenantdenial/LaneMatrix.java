package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * E5 seam (arjun-fullstack, F13): one epic lane's part of the forged-tenant matrix, in the lane's own file, so lanes
 * that add {@code /api/**} endpoints in the same epic never edit {@link EndpointMatrix} or the suite. The lane's
 * entries join {@link EndpointMatrix#REGISTRY} (so the coverage guard, every forgery and every caller apply to them
 * unchanged); its rows are rewritten for both tenants before every test, like the core config chain.
 *
 * <p>Rules for an implementation: {@link #entries()} runs while {@link EndpointMatrix} is being initialised, so it must
 * not read {@code EndpointMatrix}'s static fields - keep your own path constants, and read ids only inside the entry
 * lambdas (they run later, with the {@code Seed}). Ids of the lane's own rows come from {@link #laneId}, so they are
 * stable per tenant without a field in the shared {@code Config} record.
 */
interface LaneMatrix {

    /** The lane's endpoints, in the core registry's {@link Entry} shape. */
    List<Entry> entries();

    /**
     * Deletes the lane's rows of one tenant (owner connection, RLS bypassed). Runs BEFORE the core chain is rewritten,
     * so a lane row that references a system, endpoint or contract is gone before the core deletes its parent.
     */
    default void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
    }

    /** Inserts the lane's rows of one tenant; runs AFTER the core chain is rewritten (its parents exist again). */
    default void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
    }

    /** Values that identify this tenant's lane rows; none of them may reach the other tenant's callers. */
    default List<String> markers(SeededTenant tenant) {
        return List.of();
    }

    /**
     * One fact over the lane's tables for one tenant (for example {@code count || '@' || ids}), folded into the
     * "other tenant unchanged" fingerprint; empty when the lane writes no table of its own.
     */
    default String fingerprint(Connection owner, UUID tenantId) throws Exception {
        return "";
    }

    /** A stable id for the lane row {@code name} of {@code tenant}: the same on every call, distinct per tenant. */
    static UUID laneId(SeededTenant tenant, String name) {
        return UUID.nameUUIDFromBytes((tenant.id() + "|" + name).getBytes(StandardCharsets.UTF_8));
    }
}
