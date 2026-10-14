package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * Row 278 T84 (E7 close, lane D, vijay-developer): the forged-tenant matrix part for the row-310 queue dashboard read
 * ({@code GET /api/v1/admin/queue/summary}, QueueSummaryController, READ_MESSAGE_TIMELINE in QueueEndpointRoles).
 *
 * <p>To PROVE denial rather than list it, both tenants get one ROUTED {@code public.message} row on their main flow,
 * so each tenant's summary is non-empty: tenant A's call must answer A's main flow (id and code) and no forgery may
 * ever surface tenant B's flow id or code (both are B's markers). The endpoint has no id in its path, so the
 * PATH_ID forgery does not apply; every other forgery and caller of the suite runs against it unchanged.</p>
 */
final class F10QueueSummaryMatrix implements LaneMatrix {

    private static final String SUMMARY = "/api/v1/admin/queue/summary";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", SUMMARY, false,
                        s -> SUMMARY, null, null,
                        // the seeded ROUTED message makes the caller's main flow one dashboard row
                        s -> List.of(s.a().config().flowMainId().toString(), s.a().config().flowCode("main"))));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "DELETE FROM public.message WHERE tenant_id = ?::uuid", tenant.id().toString());
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "INSERT INTO public.message (id, tenant_id, interface_id, interface_version_id,"
                        + " source_contract_version_id, business_key, state, accepted_at)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'ROUTED', now())",
                messageId(tenant).toString(), tenant.id().toString(), tenant.config().flowMainId().toString(),
                LaneMatrix.laneId(tenant, "queue-summary-version").toString(),
                tenant.config().sourceContractVersionId().toString(), "queue-summary-" + tenant.code());
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(messageId(tenant).toString());
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (var facts = owner.prepareStatement("SELECT 'message:' || count(*) || '@'"
                + " || coalesce(string_agg(state, ',' ORDER BY id), '-') FROM public.message WHERE tenant_id = ?::uuid")) {
            facts.setString(1, tenantId.toString());
            try (var rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static UUID messageId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "queue-summary-message");
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
