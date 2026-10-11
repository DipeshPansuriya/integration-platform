package io.integrationplatform.config.rule.parity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.parity.ParityFixture.Response;
import io.integrationplatform.config.rule.parity.ParityFixture.Tenant;
import io.integrationplatform.config.rule.parity.Parity.Sample;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T-029 (E6-F3-S1, E6-C5, ADR-003 rule 3) - "Panel and runtime agree", on PostgreSQL under RLS with the production
 * composition:
 *
 * <pre>
 * Scenario: Panel and runtime agree
 *   Given a rule set and 20 sample payloads
 *   When each sample runs in the test panel and through the runtime evaluation port
 *   Then every verdict is the same (20 of 20)
 * </pre>
 *
 * The rule set is stored through the console's API for the seeded tenant; each sample runs through the panel's HTTP
 * path ({@code POST /api/v1/admin/rule-sets/{id}/evaluate}) and directly through {@link RuleEvaluationPort} with the
 * tenant bound, and both must equal the committed expected-verdicts.json (the bridge to the console's e2e proof).
 */
@Tag("container")
@SpringBootTest(classes = ParitySuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RulesPanelParityContainerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RuleEvaluationPort runtime;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        ParityFixture.wire(registry);
    }

    @Test
    @DisplayName("Panel and runtime agree: every verdict is the same, 20 of 20, and equals expected-verdicts.json")
    void panelAndRuntimeAgree() {
        Tenant tenant = ParityFixture.tenant();
        UUID ruleSetId = ParityRuns.storeRuleSet(port, tenant);

        String report = Parity.assertSameAnswer(Parity.samples(),
                sample -> ParityRuns.panelVerdict(port, tenant, ruleSetId, sample),
                sample -> ParityRuns.runtimeVerdict(runtime, tenant, ruleSetId, sample),
                Parity.expectedVerdicts());

        System.out.println("PARITY " + report);
        assertThat(report).contains("panel == runtime: 20 of 20").contains("runtime == expected-verdicts.json: 20 of 20");
    }

    @Test
    @DisplayName("The comparison is not trivially empty: 0, 1 and several violations, and routing outputs")
    void fixtureCoversEveryShapeOfVerdict() {
        Parity.assertCoverage(Parity.expectedVerdicts());
    }

    @Test
    @DisplayName("The panel answers for the stored VALIDATION rule set: its id and kind")
    void panelAnswersForTheStoredRuleSet() {
        Tenant tenant = ParityFixture.tenant();
        UUID ruleSetId = ParityRuns.storeRuleSet(port, tenant);
        Sample first = Parity.samples().getFirst();

        Response answer = ParityFixture.call(port, "POST", "/api/v1/admin/rule-sets/" + ruleSetId + "/evaluate",
                tenant, "{\"input\":" + first.input() + "}");

        assertThat(answer.status()).as("body: %s", answer.body()).isEqualTo(200);
        assertThat(answer.json().path("ruleSetId").asString()).isEqualTo(ruleSetId.toString());
        assertThat(answer.json().path("kind").asString()).isEqualTo("VALIDATION");
    }
}
