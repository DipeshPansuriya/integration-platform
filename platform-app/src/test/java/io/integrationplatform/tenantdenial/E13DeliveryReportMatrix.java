package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * Forged-tenant matrix part for the tenant delivery report ({@code GET /api/v1/reports/delivery?day=},
 * DeliveryReportController, READ_MESSAGE_TIMELINE in DeliveryReportEndpointRoles, row 327 T-139).
 *
 * <p>The tenant never travels in the path; the read is the bound tenant's day of {@code delivery_daily_aggregate},
 * stamped by the store and backed by FORCE row-level security. To PROVE denial rather than list it, both tenants get
 * one aggregate row for the same day on their own main flow: tenant A's call must answer A's flow id and no forgery
 * may surface B's flow id or target id (both are B's markers). The endpoint has no id in its path, so the PATH_ID
 * forgery does not apply; every other forgery and caller of the suite runs against it unchanged.
 */
final class E13DeliveryReportMatrix implements LaneMatrix {

    private static final String REPORT = "/api/v1/reports/delivery";
    private static final String DAY = "2026-10-01";

    @Override
    public List<Entry> entries() {
        return List.of(new Entry("GET", REPORT, false,
                s -> REPORT + "?day=" + DAY, null, null,
                s -> List.of(s.a().config().flowMainId().toString(), targetId(s.a()).toString())));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement delete = owner.prepareStatement(
                "DELETE FROM public.delivery_daily_aggregate WHERE tenant_id = ?::uuid")) {
            delete.setString(1, tenant.id().toString());
            delete.executeUpdate();
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement insert = owner.prepareStatement("INSERT INTO public.delivery_daily_aggregate"
                + " (tenant_id, day, interface_id, target_id, successes) VALUES (?::uuid, ?::date, ?::uuid, ?::uuid, 7)")) {
            insert.setString(1, tenant.id().toString());
            insert.setString(2, DAY);
            insert.setString(3, tenant.config().flowMainId().toString());
            insert.setString(4, targetId(tenant).toString());
            insert.executeUpdate();
        }
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(targetId(tenant).toString());
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT 'delivery_daily_aggregate:' || count(*) || '@'"
                + " || coalesce(string_agg(day::text || '/' || coalesce(interface_id::text, '-') || '/' || successes,"
                + " ',' ORDER BY day, interface_id), '') FROM public.delivery_daily_aggregate"
                + " WHERE tenant_id = ?::uuid")) {
            facts.setString(1, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static UUID targetId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e13-delivery-report-target");
    }
}
