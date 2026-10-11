package io.integrationplatform.config.rule.parity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.parity.Parity.Sample;
import io.integrationplatform.config.rule.parity.ParityFixture.Response;
import io.integrationplatform.config.rule.parity.ParityFixture.Tenant;
import java.util.UUID;

/** The two ways one sample is run - the panel's HTTP path and the runtime port - shared by the parity suites. */
final class ParityRuns {

    private ParityRuns() {
    }

    /** Stores the committed rule set on the tenant's flow through the console's API (201), returns its id. */
    static UUID storeRuleSet(int port, Tenant tenant) {
        Response created = ParityFixture.call(port, "POST", "/api/v1/admin/flows/" + tenant.flowId() + "/rule-sets",
                tenant, "{\"kind\":\"VALIDATION\",\"name\":\"parity " + UUID.randomUUID() + "\",\"content\":"
                        + Parity.ruleSet() + "}");
        assertThat(created.status()).as("store the parity rule set, body: %s", created.body()).isEqualTo(201);
        return UUID.fromString(created.json().path("id").asString());
    }

    /** The panel's verdict: the evaluate endpoint over real HTTP; any status but 200 is a verdict of its own. */
    static String panelVerdict(int port, Tenant tenant, UUID ruleSetId, Sample sample) {
        Response answer = ParityFixture.call(port, "POST", "/api/v1/admin/rule-sets/" + ruleSetId + "/evaluate",
                tenant, "{\"input\":" + sample.input() + "}");
        return answer.status() == 200
                ? Parity.verdictOfPanelBody(answer.json())
                : "HTTP " + answer.status() + " " + answer.body();
    }

    /** The runtime's verdict: {@link RuleEvaluationPort#evaluate} with the tenant bound. */
    static String runtimeVerdict(RuleEvaluationPort runtime, Tenant tenant, UUID ruleSetId, Sample sample) {
        return Parity.verdict(ParityFixture.asTenant(tenant, () -> runtime.evaluate(ruleSetId, sample.input())));
    }
}
