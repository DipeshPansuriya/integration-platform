package io.integrationplatform.config.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.RuleSuiteFixture.Response;
import io.integrationplatform.config.rule.RuleSuiteFixture.Tenant;
import io.integrationplatform.e6laneb.E6LaneBSuiteApp;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T-024: the ZEN engine evaluates the STORED rule set behind RuleEvaluationPort, on PostgreSQL under RLS -
 * through the test-panel endpoint {@code POST /api/v1/admin/rule-sets/{ruleSetId}/evaluate} (lanes C and D), whose
 * controller calls the port and nothing else. Another tenant's rule set is a 404 (the port's
 * RuleSetNotFoundException; the port's own contract is RuleEvaluationPortContractTest).
 */
@Tag("container")
@SpringBootTest(classes = E6LaneBSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class RuleEvaluationContainerTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        RuleSuiteFixture.wire(registry);
    }

    @Test
    @DisplayName("The test panel evaluates a stored VALIDATION rule set: three matching rows, three violations in order")
    void panelEvaluatesStoredValidationRuleSet() {
        Tenant a = RuleSuiteFixture.tenantA();
        String id = store(a, "VALIDATION", Jdm.validationTable());

        Response evaluated = call("POST", "/api/v1/admin/rule-sets/" + id + "/evaluate", a,
                "{\"input\":" + Jdm.validationInput() + "}");

        assertThat(evaluated.status()).as("body: %s", evaluated.body()).isEqualTo(200);
        JsonNode body = evaluated.json();
        assertThat(body.path("ruleSetId").asString()).isEqualTo(id);
        assertThat(body.path("kind").asString()).isEqualTo("VALIDATION");
        assertThat(body.path("violations").findValuesAsString("code"))
                .containsExactly("Z_AMOUNT_TOO_HIGH", "A_CURRENCY_UNKNOWN", "M_REFERENCE_REQUIRED");
        assertThat(body.path("violations").get(0).path("field").asString()).isEqualTo("amount");
        assertThat(body.path("violations").get(0).path("message").asString()).isEqualTo("amount must not exceed 100");
        assertThat(body.has("output")).isTrue();
    }

    @Test
    @DisplayName("The test panel evaluates a stored ROUTING rule set: the output, no violations")
    void panelEvaluatesStoredRoutingRuleSet() {
        Tenant a = RuleSuiteFixture.tenantA();
        String id = store(a, "ROUTING", Jdm.routingTable());

        Response evaluated = call("POST", "/api/v1/admin/rule-sets/" + id + "/evaluate", a,
                "{\"input\":{\"country\":\"NL\"}}");

        assertThat(evaluated.status()).as("body: %s", evaluated.body()).isEqualTo(200);
        assertThat(evaluated.json().path("output").path("target").asString()).isEqualTo("dst-eu");
        assertThat(evaluated.json().path("violations").isArray()).isTrue();
        assertThat(evaluated.json().path("violations").size()).isZero();
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("Another tenant's rule set, or an unknown id: 404 on the test panel")
    void anotherTenantsRuleSetIsNotFound() {
        Tenant a = RuleSuiteFixture.tenantA();
        Tenant b = RuleSuiteFixture.tenantB();
        String id = store(a, "VALIDATION", Jdm.validationTable());

        Response evaluated = call("POST", "/api/v1/admin/rule-sets/" + id + "/evaluate", b,
                "{\"input\":" + Jdm.validationInput() + "}");
        assertThat(evaluated.status()).isEqualTo(404);
        assertThat(evaluated.body()).doesNotContain(id).doesNotContain("Z_AMOUNT_TOO_HIGH");
        assertThat(call("POST", "/api/v1/admin/rule-sets/" + UUID.randomUUID() + "/evaluate", a,
                "{\"input\":{}}").status()).as("an unknown id").isEqualTo(404);
    }

    @Test
    @Tag("negative")
    @DisplayName("The test panel writes nothing")
    void panelWritesNothing() {
        Tenant a = RuleSuiteFixture.tenantA();
        String id = store(a, "ROUTING", Jdm.routingTable());
        String before = RuleSuiteFixture.ownerText("SELECT updated_at::text || '|' || content_text"
                + " FROM public.rule_set WHERE id = ?::uuid", id);

        call("POST", "/api/v1/admin/rule-sets/" + id + "/evaluate", a, "{\"input\":{\"country\":\"DE\"}}");

        assertThat(RuleSuiteFixture.ownerText("SELECT updated_at::text || '|' || content_text"
                + " FROM public.rule_set WHERE id = ?::uuid", id)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    private String store(Tenant tenant, String kind, String table) {
        Response created = call("POST", "/api/v1/admin/flows/" + tenant.flowId() + "/rule-sets", tenant,
                "{\"kind\":\"" + kind + "\",\"name\":\"eval " + UUID.randomUUID() + "\",\"content\":" + table + "}");
        assertThat(created.status()).as("store the rule set, body: %s", created.body()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    private Response call(String method, String path, Tenant caller, String body) {
        return RuleSuiteFixture.call(port, method, path, caller, body);
    }
}
