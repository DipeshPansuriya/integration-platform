package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * Row 278 gate fix (T111, E13 N2, amit-developer): the forged-tenant matrix part for the operator delivery actions -
 * {@code POST /api/v1/admin/messages/deliveries/{deliveryId}/replay} and {@code .../retry}
 * (DeliveryOperatorController, REPLAY_DEAD_LETTER in DeliveryOperatorEndpointRoles).
 *
 * <p>To PROVE denial rather than list it, both tenants get two pinned deliveries: one {@code DEAD_LETTERED} (the replay
 * target) and one {@code RETRY_WAIT} (the retry target). A legitimate call moves the caller's own delivery to
 * {@code DUE} (the write probe); the {@code PATH_ID} forgery names tenant B's delivery and must answer 404 with one
 * recorded attempt, leave tenant A's delivery in its seeded state and tenant B's byte-identical (the fingerprint).
 */
final class T111DeliveryOperatorMatrix implements LaneMatrix {

    private static final String DELIVERIES = "/api/v1/admin/messages/deliveries";

    @Override
    public List<Entry> entries() {
        return List.of(entry("replay", "dead-lettered", "DEAD_LETTERED"), entry("retry", "retry-wait", "RETRY_WAIT"));
    }

    private static Entry entry(String action, String name, String seeded) {
        return new Entry("POST", DELIVERIES + "/{deliveryId}/" + action, false,
                s -> DELIVERIES + "/" + deliveryId(s.a(), name) + "/" + action,
                s -> DELIVERIES + "/" + deliveryId(s.b(), name) + "/" + action,
                null,
                s -> List.of(deliveryId(s.a(), name).toString()),
                200,
                new WriteProbe(s -> "SELECT state FROM public.delivery WHERE id = '" + deliveryId(s.a(), name) + "'",
                        seeded, "DUE"));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        for (String name : List.of("dead-lettered", "retry-wait")) {
            String id = deliveryId(tenant, name).toString();
            update(owner, "DELETE FROM public.outbox WHERE tenant_id = ?::uuid AND aggregate_id = ?::uuid",
                    tenant.id().toString(), id);
            update(owner, "DELETE FROM public.delivery_event WHERE tenant_id = ?::uuid AND delivery_id = ?::uuid",
                    tenant.id().toString(), id);
            update(owner, "DELETE FROM public.delivery WHERE tenant_id = ?::uuid AND id = ?::uuid",
                    tenant.id().toString(), id);
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        insert(owner, tenant, "dead-lettered", "DEAD_LETTERED", "PERMANENT");
        insert(owner, tenant, "retry-wait", "RETRY_WAIT", null);
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(deliveryId(tenant, "dead-lettered").toString(), deliveryId(tenant, "retry-wait").toString());
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement("SELECT 'delivery:' || count(*) || '@'"
                + " || coalesce(string_agg(id::text || '=' || state || '/' || updated_at::text, ',' ORDER BY id), '')"
                + " || ';delivery_event:' || (SELECT count(*) FROM public.delivery_event WHERE tenant_id = ?::uuid)"
                + " FROM public.delivery WHERE tenant_id = ?::uuid")) {
            statement.setString(1, tenantId.toString());
            statement.setString(2, tenantId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insert(Connection owner, SeededTenant tenant, String name, String state,
            String deadLetterClass) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement("INSERT INTO public.delivery (id, tenant_id, state,"
                + " created_at, updated_at, mapping_version_id, contract_version_id, dead_letter_class)"
                + " VALUES (?::uuid, ?::uuid, ?, now(), now(), ?::uuid, ?::uuid, ?)")) {
            statement.setString(1, deliveryId(tenant, name).toString());
            statement.setString(2, tenant.id().toString());
            statement.setString(3, state);
            statement.setString(4, LaneMatrix.laneId(tenant, "t111-mapping-version").toString());
            statement.setString(5, tenant.config().sourceContractVersionId().toString());
            statement.setString(6, deadLetterClass);
            statement.executeUpdate();
        }
    }

    private static UUID deliveryId(SeededTenant tenant, String name) {
        return LaneMatrix.laneId(tenant, "t111-delivery-" + name);
    }

    private static void update(Connection owner, String sql, String... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }
}
