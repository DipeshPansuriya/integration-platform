package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * E7 lane B1 (sanjay-developer, T51: T-163): the forged-tenant matrix part for the lookup-entry edit API
 * ({@code LookupAdminController} in {@code config/rule/adapter/in}). Each tenant gets one LOOKUP rule set on its
 * main flow with one lookup_table row naming it, two entries (r1 NL -> Netherlands, r2 DE -> Germany); every entry
 * targets it (tenant A) or the other tenant's (the forged id). Only that lane edits this file and never
 * {@link EndpointMatrix}; see {@link LaneMatrix} for the rules.
 */
final class E7LookupMatrix implements LaneMatrix {

    private static final String LOOKUPS = "/api/v1/admin/lookups";

    /** A two-entry LOOKUP decision table in the open ZEN JSON format: country code to its name. */
    static final String LOOKUP_JDM = "{\"nodes\":[{\"id\":\"request\",\"type\":\"inputNode\",\"name\":\"Request\","
            + "\"position\":{\"x\":0,\"y\":0}},{\"id\":\"codes\",\"type\":\"decisionTableNode\",\"name\":\"Codes\","
            + "\"position\":{\"x\":240,\"y\":0},\"content\":{\"hitPolicy\":\"first\",\"inputs\":[{\"id\":\"c1\","
            + "\"name\":\"Code\",\"field\":\"code\"}],\"outputs\":[{\"id\":\"o1\",\"name\":\"Name\","
            + "\"field\":\"name\"}],\"rules\":[{\"_id\":\"r1\",\"c1\":\"\\\"NL\\\"\",\"o1\":\"\\\"Netherlands\\\"\"},"
            + "{\"_id\":\"r2\",\"c1\":\"\\\"DE\\\"\",\"o1\":\"\\\"Germany\\\"\"}]}},"
            + "{\"id\":\"response\",\"type\":\"outputNode\",\"name\":\"Response\",\"position\":{\"x\":480,\"y\":0}}],"
            + "\"edges\":[{\"id\":\"e1\",\"sourceId\":\"request\",\"targetId\":\"codes\",\"type\":\"edge\"},"
            + "{\"id\":\"e2\",\"sourceId\":\"codes\",\"targetId\":\"response\",\"type\":\"edge\"}]}";

    static UUID lookupId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "lookup");
    }

    static String lookupName(SeededTenant tenant) {
        return "lookups-" + tenant.config().word();
    }

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", LOOKUPS + "/{lookupId}/entries", false,
                        s -> LOOKUPS + "/" + lookupId(s.a()) + "/entries",
                        s -> LOOKUPS + "/" + lookupId(s.b()) + "/entries",
                        null,
                        s -> List.of(lookupId(s.a()).toString(), "Netherlands", "Germany")),
                new Entry("PUT", LOOKUPS + "/{lookupId}/entries/{entryId}", true,
                        s -> LOOKUPS + "/" + lookupId(s.a()) + "/entries/r1",
                        s -> LOOKUPS + "/" + lookupId(s.b()) + "/entries/r1",
                        s -> "{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}",
                        s -> List.of(lookupId(s.a()).toString(), "Kingdom of the Netherlands"),
                        200,
                        new WriteProbe(s -> "SELECT (content_text"
                                + " LIKE '%Kingdom of the Netherlands%')::text FROM public.rule_set WHERE id = '"
                                + lookupId(s.a()) + "'", "false", "true")));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        for (String table : List.of("lookup_table", "rule_set")) {
            update(owner, "DELETE FROM public." + table + " WHERE tenant_id = ?::uuid", tenant.id().toString());
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        update(owner, "INSERT INTO public.rule_set (id, tenant_id, interface_id, kind, name, content, content_text,"
                + " author_id) VALUES (?::uuid, ?::uuid, ?::uuid, 'LOOKUP', ?, ?::jsonb, ?, ?::uuid)",
                lookupId(tenant).toString(), tenant.id().toString(), tenant.config().flowMainId().toString(),
                lookupName(tenant), LOOKUP_JDM, LOOKUP_JDM, tenant.userId().toString());
        update(owner, "INSERT INTO public.lookup_table (id, tenant_id, rule_set_id, name)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?)",
                LaneMatrix.laneId(tenant, "lookup-table").toString(), tenant.id().toString(),
                lookupId(tenant).toString(), lookupName(tenant));
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(lookupId(tenant).toString(), lookupName(tenant));
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (var facts = owner.prepareStatement("SELECT"
                + " (SELECT 'lookup_table:' || count(*) || '@' || coalesce(string_agg(name, ','), '-')"
                + " FROM public.lookup_table WHERE tenant_id = ?::uuid)")) {
            facts.setString(1, tenantId.toString());
            try (var rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
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