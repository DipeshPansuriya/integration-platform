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
 * E6 lane B (sanjay-developer, T47: T-023, T-024): the forged-tenant matrix part for the rule-set admin API and the
 * test panel. Each tenant gets one ROUTING rule set on its main flow; every entry targets it (tenant A) or the other
 * tenant's (the forged id). Only that lane edits this file; see {@link LaneMatrix} for the rules.
 */
final class E6RuleMatrix implements LaneMatrix {

    private static final String FLOWS = "/api/v1/admin/flows";
    private static final String RULE_SETS = "/api/v1/admin/rule-sets";

    /** A one-row ROUTING decision table in the open ZEN JSON format: country NL routes to "dst-eu". */
    static final String RULES_JDM = "{\"nodes\":[{\"id\":\"request\",\"type\":\"inputNode\",\"name\":\"Request\","
            + "\"position\":{\"x\":0,\"y\":0}},{\"id\":\"route\",\"type\":\"decisionTableNode\",\"name\":\"Route\","
            + "\"position\":{\"x\":240,\"y\":0},\"content\":{\"hitPolicy\":\"first\",\"inputs\":[{\"id\":\"c1\","
            + "\"name\":\"Country\",\"field\":\"country\"}],\"outputs\":[{\"id\":\"o1\",\"name\":\"Target\","
            + "\"field\":\"target\"}],\"rules\":[{\"_id\":\"r1\",\"c1\":\"\\\"NL\\\"\",\"o1\":\"\\\"dst-eu\\\"\"}]}},"
            + "{\"id\":\"response\",\"type\":\"outputNode\",\"name\":\"Response\",\"position\":{\"x\":480,\"y\":0}}],"
            + "\"edges\":[{\"id\":\"e1\",\"sourceId\":\"request\",\"targetId\":\"route\",\"type\":\"edge\"},"
            + "{\"id\":\"e2\",\"sourceId\":\"route\",\"targetId\":\"response\",\"type\":\"edge\"}]}";

    static UUID ruleSetId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "rule-set");
    }

    static String ruleSetName(SeededTenant tenant) {
        return "rules-" + tenant.config().word();
    }

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("POST", FLOWS + "/{flowId}/rule-sets", true,
                        s -> FLOWS + "/" + s.a().config().flowMainId() + "/rule-sets",
                        s -> FLOWS + "/" + s.b().config().flowMainId() + "/rule-sets",
                        s -> "{\"kind\":\"ROUTING\",\"name\":\"fresh rules\",\"content\":" + RULES_JDM + "}",
                        s -> List.of(s.a().config().flowMainId().toString(), "fresh rules"),
                        201,
                        new WriteProbe(s -> "SELECT count(*)::text FROM public.rule_set WHERE tenant_id = '"
                                + s.a().id() + "'", "1", "2")),
                new Entry("GET", FLOWS + "/{flowId}/rule-sets", false,
                        s -> FLOWS + "/" + s.a().config().flowMainId() + "/rule-sets",
                        s -> FLOWS + "/" + s.b().config().flowMainId() + "/rule-sets",
                        null,
                        s -> List.of(ruleSetId(s.a()).toString(), ruleSetName(s.a()))),
                new Entry("GET", RULE_SETS + "/{ruleSetId}", false,
                        s -> RULE_SETS + "/" + ruleSetId(s.a()),
                        s -> RULE_SETS + "/" + ruleSetId(s.b()),
                        null,
                        s -> List.of(ruleSetId(s.a()).toString(), ruleSetName(s.a()),
                                s.a().config().flowMainId().toString())),
                new Entry("PUT", RULE_SETS + "/{ruleSetId}", true,
                        s -> RULE_SETS + "/" + ruleSetId(s.a()),
                        s -> RULE_SETS + "/" + ruleSetId(s.b()),
                        s -> "{\"name\":\"renamed rules\",\"content\":" + RULES_JDM + "}",
                        s -> List.of(ruleSetId(s.a()).toString(), "renamed rules"),
                        200,
                        new WriteProbe(s -> "SELECT (name = 'renamed rules')::text FROM public.rule_set WHERE id = '"
                                + ruleSetId(s.a()) + "'", "false", "true")),
                new Entry("POST", RULE_SETS + "/{ruleSetId}/evaluate", true,
                        s -> RULE_SETS + "/" + ruleSetId(s.a()) + "/evaluate",
                        s -> RULE_SETS + "/" + ruleSetId(s.b()) + "/evaluate",
                        s -> "{\"input\":{\"country\":\"NL\"}}",
                        s -> List.of(ruleSetId(s.a()).toString(), "dst-eu")));
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
                        + " author_id) VALUES (?::uuid, ?::uuid, ?::uuid, 'ROUTING', ?, ?::jsonb, ?, ?::uuid)",
                ruleSetId(tenant).toString(), tenant.id().toString(), tenant.config().flowMainId().toString(),
                ruleSetName(tenant), RULES_JDM, RULES_JDM, tenant.userId().toString());
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(ruleSetId(tenant).toString(), ruleSetName(tenant));
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT"
                + " (SELECT 'rule_set:' || count(*) || '@' || coalesce(string_agg(id::text || '=' || name || '#'"
                + "   || md5(content_text), ',' ORDER BY id), '') FROM public.rule_set WHERE tenant_id = ?::uuid)"
                + " || ';' || (SELECT 'lookup_table:' || count(*) || '@' || coalesce(string_agg(id::text || '='"
                + "   || name, ',' ORDER BY id), '') FROM public.lookup_table WHERE tenant_id = ?::uuid)")) {
            facts.setString(1, tenantId.toString());
            facts.setString(2, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void update(Connection owner, String sql, String... args) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
