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
 * E16 F3: the forged-tenant matrix part for the approved tables of a DB system ({@code ApprovedTableAdminController}:
 * list, request, approve, revoke; EDIT_CONFIGURATION to request, APPROVE_DB_TABLE to approve or revoke, both held by
 * the matrix's TENANT_ADMIN, so a denial is never a missing role).
 *
 * <p>Both tenants get their core chain's source system typed DB (a request needs a DB system), one PENDING_APPROVAL row
 * requested by someone else (the approve target: the four-eyes rule refuses the requester approving, so the seeded
 * requester is never the matrix user) and one APPROVED row (the revoke target). The rows carry tenant-specific table
 * names, so a leak of tenant B's rows shows. The legitimate calls move tenant A's own row (write probes); the PATH_ID
 * forgery names tenant B's system and row: 404, tenant A's rows untouched, tenant B's rows byte-identical (fingerprint).
 */
final class E16ApprovedTableMatrix implements LaneMatrix {

    private static final String SYSTEMS = "/api/v1/admin/systems";
    private static final String TABLES = "/approved-tables";
    private static final String DB_SETTINGS =
            "{\"host\":\"db.partner.invalid\",\"port\":5432,\"database\":\"orders\",\"user\":\"reader\"}";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", SYSTEMS + "/{systemId}" + TABLES, false,
                        s -> base(s.a()), s -> base(s.b()), null,
                        s -> List.of(pendingId(s.a()).toString(), pendingTable(s.a()), approvedId(s.a()).toString(),
                                approvedTable(s.a()))),
                new Entry("POST", SYSTEMS + "/{systemId}" + TABLES, true,
                        s -> base(s.a()), s -> base(s.b()),
                        s -> "{\"table\":\"" + requestedTable(s.a()) + "\"}",
                        s -> List.of(requestedTable(s.a()), "PENDING_APPROVAL"),
                        201,
                        new WriteProbe(s -> "SELECT count(*) FROM public.db_target_approved_table WHERE tenant_id = '"
                                + s.a().id() + "'", "2", "3")),
                new Entry("POST", SYSTEMS + "/{systemId}" + TABLES + "/{id}/approve", false,
                        s -> base(s.a()) + "/" + pendingId(s.a()) + "/approve",
                        s -> base(s.b()) + "/" + pendingId(s.b()) + "/approve",
                        null,
                        s -> List.of(pendingId(s.a()).toString(), "APPROVED"),
                        200,
                        new WriteProbe(s -> statusOf(pendingId(s.a())), "PENDING_APPROVAL", "APPROVED")),
                new Entry("POST", SYSTEMS + "/{systemId}" + TABLES + "/{id}/revoke", false,
                        s -> base(s.a()) + "/" + approvedId(s.a()) + "/revoke",
                        s -> base(s.b()) + "/" + approvedId(s.b()) + "/revoke",
                        null,
                        s -> List.of(approvedId(s.a()).toString(), "REVOKED"),
                        200,
                        new WriteProbe(s -> statusOf(approvedId(s.a())), "APPROVED", "REVOKED")));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement delete = owner.prepareStatement(
                "DELETE FROM public.db_target_approved_table WHERE tenant_id = ?::uuid")) {
            delete.setString(1, tenant.id().toString());
            delete.executeUpdate();
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        // type and settings only change together (system_type_and_settings_together); the source system is the DB one
        try (PreparedStatement typed = owner.prepareStatement("UPDATE public.system SET type = 'DB',"
                + " settings = ?::jsonb WHERE tenant_id = ?::uuid AND id = ?::uuid")) {
            typed.setString(1, DB_SETTINGS);
            typed.setString(2, tenant.id().toString());
            typed.setString(3, tenant.config().srcSystemId().toString());
            if (typed.executeUpdate() != 1) {
                throw new IllegalStateException("the core chain's source system of " + tenant.code() + " is missing");
            }
        }
        insert(owner, tenant, pendingId(tenant), pendingTable(tenant), "PENDING_APPROVAL", null);
        insert(owner, tenant, approvedId(tenant), approvedTable(tenant), "APPROVED", "seed-approver");
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(pendingId(tenant).toString(), approvedId(tenant).toString(), pendingTable(tenant),
                approvedTable(tenant), requestedTable(tenant));
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT 'approved_table:' || count(*) || '@'"
                + " || coalesce(string_agg(id::text || '=' || status || '/' || table_name, ',' ORDER BY id), '')"
                + " FROM public.db_target_approved_table WHERE tenant_id = ?::uuid")) {
            facts.setString(1, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insert(Connection owner, SeededTenant tenant, UUID id, String table, String status,
            String decidedBy) throws Exception {
        try (PreparedStatement insert = owner.prepareStatement("INSERT INTO public.db_target_approved_table"
                + " (id, tenant_id, system_id, table_name, status, requested_by, decided_by, decided_at, target_hash)"
                + " SELECT ?::uuid, s.tenant_id, s.id, ?, ?, 'seed-requester', ?::text,"
                + " CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, public.db_target_fingerprint(s.settings)"
                + " FROM public.system s WHERE s.tenant_id = ?::uuid AND s.id = ?::uuid")) {
            insert.setString(1, id.toString());
            insert.setString(2, table);
            insert.setString(3, status);
            insert.setString(4, decidedBy);
            insert.setString(5, decidedBy);
            insert.setString(6, tenant.id().toString());
            insert.setString(7, tenant.config().srcSystemId().toString());
            if (insert.executeUpdate() != 1) {
                throw new IllegalStateException("the approved-table row of " + tenant.code() + " was not written");
            }
        }
    }

    private static String base(SeededTenant tenant) {
        return SYSTEMS + "/" + tenant.config().srcSystemId() + TABLES;
    }

    private static String statusOf(UUID rowId) {
        return "SELECT status FROM public.db_target_approved_table WHERE id = '" + rowId + "'";
    }

    private static UUID pendingId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e16-table-pending");
    }

    private static UUID approvedId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e16-table-approved");
    }

    private static String pendingTable(SeededTenant tenant) {
        return "tbl_" + tenant.config().word() + "_pending";
    }

    private static String approvedTable(SeededTenant tenant) {
        return "tbl_" + tenant.config().word() + "_approved";
    }

    private static String requestedTable(SeededTenant tenant) {
        return "tbl_" + tenant.config().word() + "_requested";
    }
}
