package io.integrationplatform.config.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.RuleSuiteFixture.Response;
import io.integrationplatform.config.rule.RuleSuiteFixture.Tenant;
import io.integrationplatform.e6laneb.E6LaneBSuiteApp;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T-023 (E6-F1-S1): rule sets and lookup tables are configuration rows authored through the admin API, against a
 * real PostgreSQL under RLS and the real identity chain. The cross-lane contract (lanes C and D code against it):
 * {@code POST/GET /api/v1/admin/flows/{flowId}/rule-sets}, {@code GET/PUT /api/v1/admin/rule-sets/{ruleSetId}}.
 *
 * <pre>
 * Scenario Outline: Author each kind of rule
 *   Given the rules editor on an interface
 *   When a configurator authors a &lt;kind&gt; as a decision table
 *   Then it is stored as configuration rows in the open ZEN JSON format and re-opens exactly as left
 *   Examples:
 *     | kind                      |
 *     | validation rule           |
 *     | conditional routing rule  |
 *     | lookup table              |
 * &#64;negative
 * Scenario: A malformed decision table is refused
 *   Given a decision table with an empty condition column or an unparsable expression
 *   When it is saved
 *   Then it is refused with the cell and the reason
 * &#64;negative &#64;tenant
 * Scenario: Rules are tenant-scoped
 *   Given a configurator of tenant B
 *   When they open tenant A's rule set by id
 *   Then they get 404
 * </pre>
 */
@Tag("container")
@SpringBootTest(classes = E6LaneBSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class RuleSetAdminContainerTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        RuleSuiteFixture.wire(registry);
    }

    static Stream<Arguments> kinds() {
        return Stream.of(
                Arguments.of("validation rule", "VALIDATION", Jdm.validationTable()),
                Arguments.of("conditional routing rule", "ROUTING", Jdm.routingTable()),
                Arguments.of("lookup table", "LOOKUP", Jdm.lookupTable()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    @DisplayName("Scenario Outline: Author each kind of rule - stored as rows in ZEN JSON, re-opens exactly as left")
    void authorEachKindOfRule(String example, String kind, String table) {
        Tenant a = RuleSuiteFixture.tenantA();
        String name = example + " " + UUID.randomUUID();

        Response created = create(a, a.flowId(), kind, name, table);
        assertThat(created.status()).as("POST rule set, body: %s", created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(created.json().path("flowId").asString()).isEqualTo(a.flowId().toString());
        assertThat(created.json().path("kind").asString()).isEqualTo(kind);
        assertThat(created.json().path("name").asString()).isEqualTo(name);

        // re-opens exactly as left: the content comes back byte for byte
        Response reopened = call("GET", "/api/v1/admin/rule-sets/" + id, a, null);
        assertThat(reopened.status()).as("GET rule set, body: %s", reopened.body()).isEqualTo(200);
        assertThat(reopened.body()).as("the content re-opens byte for byte").endsWith("\"content\":" + table + "}");
        assertThat(reopened.json().path("kind").asString()).isEqualTo(kind);

        Response listed = call("GET", "/api/v1/admin/flows/" + a.flowId() + "/rule-sets", a, null);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.json().findValuesAsString("id")).contains(id);

        // stored as configuration rows in the open ZEN JSON format (jsonb), owner's view of the row
        assertThat(RuleSuiteFixture.ownerText("SELECT kind || '|' || jsonb_typeof(content) || '|'"
                + " || (content->'nodes'->1->>'type') FROM public.rule_set WHERE id = ?::uuid", id))
                .isEqualTo(kind + "|object|decisionTableNode");
        assertThat(RuleSuiteFixture.ownerText("SELECT content_text FROM public.rule_set WHERE id = ?::uuid", id))
                .isEqualTo(table);
        assertThat(RuleSuiteFixture.ownerText("SELECT count(*)::text FROM public.lookup_table"
                + " WHERE rule_set_id = ?::uuid", id))
                .as("a lookup table is registered by name for LOOKUP rule sets only")
                .isEqualTo(kind.equals("LOOKUP") ? "1" : "0");
    }

    @Test
    @DisplayName("A saved rule set is edited in place: PUT changes name and content, re-opens as left")
    void ruleSetIsEditedInPlace() {
        Tenant a = RuleSuiteFixture.tenantA();
        String id = create(a, a.flowId(), "ROUTING", "routing " + UUID.randomUUID(), Jdm.routingTable())
                .json().path("id").asString();

        Response updated = call("PUT", "/api/v1/admin/rule-sets/" + id, a,
                "{\"name\":\"renamed\",\"content\":" + Jdm.lookupTable() + "}");
        assertThat(updated.status()).as("PUT, body: %s", updated.body()).isEqualTo(200);

        Response reopened = call("GET", "/api/v1/admin/rule-sets/" + id, a, null);
        assertThat(reopened.json().path("name").asString()).isEqualTo("renamed");
        assertThat(reopened.json().path("kind").asString()).as("the kind is fixed at creation").isEqualTo("ROUTING");
        assertThat(reopened.body()).endsWith("\"content\":" + Jdm.lookupTable() + "}");
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: A malformed decision table is refused - an unparsable expression names its cell")
    void unparsableExpressionIsRefusedWithItsCell() {
        Tenant a = RuleSuiteFixture.tenantA();
        String before = ruleSetCount(a);

        Response refused = create(a, a.flowId(), "VALIDATION", "bad expression", Jdm.unparsableExpression());

        assertThat(refused.status()).as("body: %s", refused.body()).isEqualTo(400);
        JsonNode body = refused.json();
        assertThat(body.path("error").asString()).isEqualTo("RULE_TABLE_INVALID");
        assertThat(body.path("cell").path("node").asString()).isEqualTo(Jdm.TABLE_NODE);
        assertThat(body.path("cell").path("row").asInt()).as("rules[1], zero-based").isEqualTo(1);
        assertThat(body.path("cell").path("column").asString()).isEqualTo("c1");
        assertThat(body.path("reason").asString()).as("the engine's own reason").isNotBlank();
        assertThat(ruleSetCount(a)).as("nothing stored").isEqualTo(before);
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: A malformed decision table is refused - an empty condition column names its column")
    void emptyConditionColumnIsRefusedWithItsColumn() {
        Tenant a = RuleSuiteFixture.tenantA();
        String before = ruleSetCount(a);

        Response refused = create(a, a.flowId(), "VALIDATION", "empty column", Jdm.emptyConditionColumn());

        assertThat(refused.status()).as("body: %s", refused.body()).isEqualTo(400);
        JsonNode body = refused.json();
        assertThat(body.path("error").asString()).isEqualTo("RULE_TABLE_INVALID");
        assertThat(body.path("cell").path("node").asString()).isEqualTo(Jdm.TABLE_NODE);
        assertThat(body.path("cell").path("row").isNull()).as("a column-level problem has no row").isTrue();
        assertThat(body.path("cell").path("column").asString()).isEqualTo("c9");
        assertThat(body.path("reason").asString()).isNotBlank();
        assertThat(ruleSetCount(a)).as("nothing stored").isEqualTo(before);
    }

    @Test
    @Tag("negative")
    @DisplayName("A malformed edit is refused the same way and leaves the stored content as it was")
    void malformedEditIsRefused() {
        Tenant a = RuleSuiteFixture.tenantA();
        String id = create(a, a.flowId(), "VALIDATION", "kept " + UUID.randomUUID(), Jdm.validationTable())
                .json().path("id").asString();

        Response refused = call("PUT", "/api/v1/admin/rule-sets/" + id, a,
                "{\"name\":\"kept\",\"content\":" + Jdm.unparsableExpression() + "}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.json().path("error").asString()).isEqualTo("RULE_TABLE_INVALID");
        assertThat(call("GET", "/api/v1/admin/rule-sets/" + id, a, null).body())
                .endsWith("\"content\":" + Jdm.validationTable() + "}");
    }

    @Test
    @Tag("negative")
    @DisplayName("An unknown kind or a content that is not a decision model is a 400")
    void unknownKindAndNonObjectContentAreRefused() {
        Tenant a = RuleSuiteFixture.tenantA();
        assertThat(create(a, a.flowId(), "MAPPING", "x", Jdm.routingTable()).status()).isEqualTo(400);
        Response notAModel = create(a, a.flowId(), "ROUTING", "x", "\"just text\"");
        assertThat(notAModel.status()).isEqualTo(400);
        assertThat(notAModel.json().path("error").asString()).isEqualTo("RULE_TABLE_INVALID");
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("Scenario: Rules are tenant-scoped - tenant B opening tenant A's rule set by id gets 404")
    void rulesAreTenantScoped() {
        Tenant a = RuleSuiteFixture.tenantA();
        Tenant b = RuleSuiteFixture.tenantB();
        String name = "tenant-a-only " + UUID.randomUUID();
        String id = create(a, a.flowId(), "VALIDATION", name, Jdm.validationTable()).json().path("id").asString();

        Response opened = call("GET", "/api/v1/admin/rule-sets/" + id, b, null);
        assertThat(opened.status()).isEqualTo(404);
        assertThat(opened.body()).doesNotContain(id).doesNotContain(name).doesNotContain(a.flowId().toString());

        assertThat(call("PUT", "/api/v1/admin/rule-sets/" + id, b,
                "{\"name\":\"hijack\",\"content\":" + Jdm.routingTable() + "}").status()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/admin/flows/" + a.flowId() + "/rule-sets", b, null).status())
                .isEqualTo(404);
        assertThat(create(b, a.flowId(), "ROUTING", "into A", Jdm.routingTable()).status()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/admin/rule-sets/" + UUID.randomUUID(), a, null).status())
                .as("an unknown id is the same 404").isEqualTo(404);
        assertThat(call("GET", "/api/v1/admin/rule-sets/not-a-uuid", a, null).status())
                .as("a malformed id is the same 404, never a 500").isEqualTo(404);
        assertThat(RuleSuiteFixture.ownerText("SELECT name FROM public.rule_set WHERE id = ?::uuid", id))
                .as("tenant A's row is untouched").isEqualTo(name);
    }

    // ------------------------------------------------------------------ helpers

    private Response create(Tenant caller, UUID flowId, String kind, String name, String content) {
        return call("POST", "/api/v1/admin/flows/" + flowId + "/rule-sets", caller,
                "{\"kind\":\"" + kind + "\",\"name\":\"" + name + "\",\"content\":" + content + "}");
    }

    private Response call(String method, String path, Tenant caller, String body) {
        return RuleSuiteFixture.call(port, method, path, caller, body);
    }

    private static String ruleSetCount(Tenant tenant) {
        return RuleSuiteFixture.ownerText("SELECT count(*)::text FROM public.rule_set WHERE tenant_id = ?::uuid",
                tenant.id());
    }
}
