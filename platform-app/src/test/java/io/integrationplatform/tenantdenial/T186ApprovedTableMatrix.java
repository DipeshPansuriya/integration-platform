package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

/**
 * T186 (stale-test fix, arjun-fullstack): the forged-tenant matrix part for the E16 F3 approved-table endpoints of a DB
 * system ({@code ApprovedTableAdminController}; roles in SystemEndpointRoles: list READ_CONFIGURATION, request
 * EDIT_CONFIGURATION, approve and revoke APPROVE_DB_TABLE). The tenant never travels in the path, the body or a
 * header: it comes from the validated token, and a system or row id of another tenant is hidden by RLS (404).
 *
 * <p>Rows per tenant (rewritten before every test): the core chain's untyped SOURCE system is typed DB (so the
 * system count the core entries pin does not move), plus two approved-table rows - one PENDING_APPROVAL (the approve
 * target) and one APPROVED (the revoke target); both are the listing data. A legitimate request adds one PENDING row;
 * a legitimate revoke moves the APPROVED row to REVOKED. The approve leg answers 409 in this harness: T178a fails
 * closed (DB_ACCOUNT_UNVERIFIED) because the seeded system points at no real database, so the row must stay
 * PENDING_APPROVAL - the probe pins that; the success path of an approval is the approved-table service tests'.
 */
final class T186ApprovedTableMatrix implements LaneMatrix {

    private static final String TABLES = "/api/v1/admin/systems/{systemId}/approved-tables";
    private static final String SYSTEMS = "/api/v1/admin/systems/";
    private static final String SETTINGS = "{\"host\":\"db.invalid\",\"port\":5432,\"database\":\"t186\","
            + "\"user\":\"t186_narrow\",\"table\":\"t186_target\"}";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", TABLES, false,
                        s -> tablesPath(s.a()), s -> tablesPath(s.b()), null,
                        s -> List.of(tableName(s.a(), "pending"), tableName(s.a(), "approved"))),
                new Entry("POST", TABLES, true,
                        s -> tablesPath(s.a()), s -> tablesPath(s.b()),
                        s -> "{\"table\":\"" + tableName(s.a(), "fresh") + "\"}",
                        s -> List.of(tableName(s.a(), "fresh"), "PENDING_APPROVAL"),
                        201,
                        new WriteProbe(s -> "SELECT count(*) FROM public.db_target_approved_table WHERE tenant_id = '"
                                + s.a().id() + "'", "2", "3")),
                // 409, not a success: the width probe cannot verify the seeded system (fail closed), nothing moves
                new Entry("POST", TABLES + "/{id}/approve", false,
                        s -> tablesPath(s.a()) + "/" + rowId(s.a(), "pending") + "/approve",
                        s -> tablesPath(s.b()) + "/" + rowId(s.b(), "pending") + "/approve",
                        null,
                        s -> List.of(),
                        409,
                        new WriteProbe(s -> statusOf(s.a(), "pending"), "PENDING_APPROVAL", "PENDING_APPROVAL")),
                new Entry("POST", TABLES + "/{id}/revoke", false,
                        s -> tablesPath(s.a()) + "/" + rowId(s.a(), "approved") + "/revoke",
                        s -> tablesPath(s.b()) + "/" + rowId(s.b(), "approved") + "/revoke",
                        null,
                        s -> List.of(rowId(s.a(), "approved").toString(), tableName(s.a(), "approved"), "REVOKED"),
                        200,
                        new WriteProbe(s -> statusOf(s.a(), "approved"), "APPROVED", "REVOKED")));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "DELETE FROM public.db_target_approved_table WHERE tenant_id = ?::uuid", tenant.id().toString());
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "UPDATE public.system SET type = 'DB', settings = ?::jsonb WHERE tenant_id = ?::uuid"
                + " AND id = ?::uuid", SETTINGS, tenant.id().toString(), tenant.config().srcSystemId().toString());
        insert(owner, tenant, "pending", "PENDING_APPROVAL", null);
        insert(owner, tenant, "approved", "APPROVED", "t186-seed-approver");
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(rowId(tenant, "pending").toString(), rowId(tenant, "approved").toString(),
                tableName(tenant, "pending"), tableName(tenant, "approved"), tableName(tenant, "fresh"));
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT 'approved_table:' || count(*) || '@'"
                + " || coalesce(string_agg(id::text || '=' || status || '/' || coalesce(decided_by, '-'), ','"
                + " ORDER BY id), '') FROM public.db_target_approved_table WHERE tenant_id = ?::uuid")) {
            facts.setString(1, tenantId.toString());
            try (var rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insert(Connection owner, SeededTenant tenant, String name, String status, String decidedBy)
            throws Exception {
        update(owner, "INSERT INTO public.db_target_approved_table (id, tenant_id, system_id, table_name, status,"
                        + " requested_by, decided_by, decided_at, target_hash)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 't186-seed-requester', ?::text,"
                        + " CASE WHEN ?::text IS NULL THEN NULL ELSE now() END,"
                        + " public.db_target_fingerprint(?::jsonb))",
                rowId(tenant, name).toString(), tenant.id().toString(), tenant.config().srcSystemId().toString(),
                tableName(tenant, name), status, decidedBy, decidedBy, SETTINGS);
    }

    private static String tablesPath(SeededTenant tenant) {
        return SYSTEMS + tenant.config().srcSystemId() + "/approved-tables";
    }

    private static String statusOf(SeededTenant tenant, String name) {
        return "SELECT status FROM public.db_target_approved_table WHERE id = '" + rowId(tenant, name) + "'";
    }

    private static UUID rowId(SeededTenant tenant, String name) {
        return LaneMatrix.laneId(tenant, "t186-approved-table-" + name);
    }

    /** A plain identifier (letters, digits, underscore) that carries the tenant's word, so a leak is visible. */
    private static String tableName(SeededTenant tenant, String name) {
        return "t186_" + name + "_" + tenant.config().word().replaceAll("[^A-Za-z0-9]", "_");
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
