package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Row 278 T110 (E13 N1, lane C, rohit-developer): the forged-tenant matrix part for the message tracking reads -
 * {@code GET /api/v1/admin/messages/{trackingId}/timeline} and {@code GET /api/v1/admin/messages/failures}
 * (MessageTrackingController, READ_MESSAGE_TIMELINE in MessageEndpointRoles). The E13 plan's
 * {@code E13TimelineMatrix} hook (another tenant's timeline is 404).
 *
 * <p>To PROVE denial rather than list it, both tenants get one {@code ROUTE_FAILED} message on their main flow with an
 * INTAKE stage row, so each tenant's timeline and failures list are non-empty: tenant A's calls must answer A's
 * message id, and no forgery may ever surface tenant B's (B's marker). The {@code PATH_ID} forgery names tenant B's
 * tracking id on the timeline and must answer 404 with one recorded attempt (the controller records it like
 * {@code ConfigApiExceptionHandler}); the failures list has no id in its path, so PATH_ID does not apply to it.
 *
 * <p>Registration is the integrator's one line in {@link EndpointMatrix#LANES} (after {@code F10QueueSummaryMatrix}
 * is fine: every lane clears before any lane writes) plus the controller in {@code EndpointMatrixCoverageTest}'s
 * handler list.
 */
final class E13TimelineMatrix implements LaneMatrix {

    private static final String MESSAGES = "/api/v1/admin/messages";
    private static final String TIMELINE = MESSAGES + "/{trackingId}/timeline";
    private static final String FAILURES = MESSAGES + "/failures";
    private static final String DELIVERED = MESSAGES + "/delivered";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", TIMELINE, false,
                        s -> MESSAGES + "/" + messageId(s.a()) + "/timeline",
                        s -> MESSAGES + "/" + messageId(s.b()) + "/timeline",
                        null,
                        s -> List.of(messageId(s.a()).toString())),
                new Entry("GET", FAILURES, false,
                        s -> FAILURES, null, null,
                        s -> List.of(messageId(s.a()).toString())),
                // S8 search: the seeded message was accepted today (UTC), so A's search for the day answers A's id
                new Entry("GET", MESSAGES, false,
                        s -> MESSAGES + "?day=" + LocalDate.now(ZoneOffset.UTC), null, null,
                        s -> List.of(messageId(s.a()).toString())),
                // delivered list: the seeded message never delivered, so A's answer is empty - the marker check
                // still proves no forgery surfaces tenant B's message
                new Entry("GET", DELIVERED, false,
                        s -> DELIVERED, null, null,
                        s -> List.of()));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "DELETE FROM public.message_stage_timing WHERE message_id = ?::uuid",
                messageId(tenant).toString());
        update(owner, "DELETE FROM public.message WHERE id = ?::uuid", messageId(tenant).toString());
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "INSERT INTO public.message (id, tenant_id, interface_id, interface_version_id,"
                        + " source_contract_version_id, business_key, state, accepted_at)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'ROUTE_FAILED', now())",
                messageId(tenant).toString(), tenant.id().toString(), tenant.config().flowMainId().toString(),
                LaneMatrix.laneId(tenant, "e13-timeline-version").toString(),
                tenant.config().sourceContractVersionId().toString(), "e13-timeline-" + tenant.code());
        update(owner, "INSERT INTO public.message_stage_timing (message_id, tenant_id, accepted_at, stage,"
                        + " started_at, finished_at) VALUES (?::uuid, ?::uuid, now(), 'INTAKE', now(), now())",
                messageId(tenant).toString(), tenant.id().toString());
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(messageId(tenant).toString());
    }

    private static UUID messageId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e13-timeline-message");
    }

    private void update(Connection owner, String sql, String... values) throws Exception {
        try (var statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }
}
