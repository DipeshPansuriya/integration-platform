package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.sql.Connection;
import java.util.List;

/**
 * Row 327 T118 (E12-F2-S2, T-069, lane t118, sanjay-developer): the forged-tenant matrix part for the tenant activity
 * view - {@code GET /api/v1/activity} and {@code GET /api/v1/activity/{eventId}} (ActivityController,
 * READ_MESSAGE_TIMELINE in ActivityEndpointRoles).
 *
 * <p>To PROVE denial rather than list it, both tenants get one audit event whose actor is the tenant's own marker, so
 * each tenant's activity list is non-empty: tenant A's calls must answer A's actor, and no forgery may ever surface
 * tenant B's. The {@code PATH_ID} forgery names tenant B's event id on the by-id read and must answer 404 with one
 * recorded attempt (the controller records it like {@code MessageTrackingController}); the list has no id in its path,
 * so PATH_ID does not apply to it.
 *
 * <p>The ledger's id is an identity column, so the seeded events take a stable per-tenant id far above anything the
 * sequence reaches in one suite ({@code OVERRIDING SYSTEM VALUE}, owner connection). The owner may delete its own
 * fixture rows; the application role still may not (V2_2_1220). Audit rows the other entries' writes append are the
 * calling tenant's own, so they never carry the other tenant's markers.
 *
 * <p>Registration is the integrator's one line in {@link EndpointMatrix#LANES} plus the controller in
 * {@code EndpointMatrixCoverageTest}'s handler list (both t109-owned files; lines in the lane's hand-back).
 */
final class E12ActivityMatrix implements LaneMatrix {

    private static final String ACTIVITY = "/api/v1/activity";
    private static final String EVENT = ACTIVITY + "/{eventId}";
    private static final long FIXTURE_ID_BASE = 9_000_000_000L;

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", ACTIVITY, false,
                        s -> ACTIVITY, null, null,
                        s -> List.of(actor(s.a()))),
                new Entry("GET", EVENT, false,
                        s -> ACTIVITY + "/" + eventId(s.a()),
                        s -> ACTIVITY + "/" + eventId(s.b()),
                        null,
                        s -> List.of(actor(s.a()))));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (var statement = owner.prepareStatement(
                "DELETE FROM public.audit_event WHERE tenant_id = ?::uuid AND id = ?")) {
            statement.setString(1, tenant.id().toString());
            statement.setLong(2, eventId(tenant));
            statement.executeUpdate();
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (var statement = owner.prepareStatement("INSERT INTO public.audit_event"
                + " (id, tenant_id, actor, action, target_ids, event_at) OVERRIDING SYSTEM VALUE"
                + " VALUES (?, ?::uuid, ?, 'E12_ACTIVITY_PROBE', '[]'::jsonb, now())")) {
            statement.setLong(1, eventId(tenant));
            statement.setString(2, tenant.id().toString());
            statement.setString(3, actor(tenant));
            statement.executeUpdate();
        }
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(actor(tenant));
    }

    private static String actor(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e12-activity-actor").toString();
    }

    private static long eventId(SeededTenant tenant) {
        return FIXTURE_ID_BASE + Math.floorMod(LaneMatrix.laneId(tenant, "e12-activity-event")
                .getMostSignificantBits(), 1_000_000_000L);
    }
}
