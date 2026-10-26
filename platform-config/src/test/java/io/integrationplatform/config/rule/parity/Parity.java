package io.integrationplatform.config.rule.parity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleViolation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-029 (E6-F3-S1, ADR-003 rule 3): the parity fixture and the one assertion both sides are judged by. The committed
 * files under {@code rule-parity/} are the bridge between the backend proof and the console's e2e proof
 * (e2e-ui/tests/screens/rules-parity.docker.spec.mjs reads the same three files):
 * <ul>
 *   <li>{@code validation-rule-set.json} - one VALIDATION rule set (ZEN JDM, hitPolicy collect) whose rows report
 *       violations AND routing outputs;</li>
 *   <li>{@code samples.json} - 20 payloads with 0, 1 and several violations, with and without routing outputs;</li>
 *   <li>{@code expected-verdicts.json} - the verdict each sample must get, written from the table's meaning.</li>
 * </ul>
 * A verdict is the canonical JSON of {@code {output, violations}}: keys sorted at every depth, no whitespace - the
 * exact text the console's {@code [data-test-panel-verdict]} shows.
 */
final class Parity {

    static final int SAMPLE_COUNT = 20;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    record Sample(String id, JsonNode input) {
    }

    private Parity() {
    }

    // ------------------------------------------------------------------ the committed fixture

    /** The rule set's JDM as committed (posted as the rule set's content). */
    static JsonNode ruleSet() {
        return JSON.readTree(resource("validation-rule-set.json"));
    }

    static List<Sample> samples() {
        List<Sample> samples = new ArrayList<>();
        for (JsonNode sample : JSON.readTree(resource("samples.json"))) {
            samples.add(new Sample(sample.path("id").asString(), sample.path("input")));
        }
        return samples;
    }

    /** Sample id to its expected canonical verdict, in file order. */
    static Map<String, String> expectedVerdicts() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (JsonNode entry : JSON.readTree(resource("expected-verdicts.json"))) {
            expected.put(entry.path("id").asString(), canonical(entry.path("verdict")));
        }
        return expected;
    }

    private static String resource(String name) {
        try (InputStream in = Parity.class.getResourceAsStream("/rule-parity/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource rule-parity/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ verdicts

    /** The runtime port's answer as a verdict. */
    static String verdict(RuleEvaluation evaluation) {
        ArrayNode violations = NODES.arrayNode();
        for (RuleViolation violation : evaluation.violations()) {
            ObjectNode node = NODES.objectNode();
            node.put("field", violation.field());
            node.put("code", violation.code());
            node.put("message", violation.message());
            violations.add(node);
        }
        return verdict(evaluation.output(), violations);
    }

    /** The panel endpoint's 200 body as a verdict. */
    static String verdictOfPanelBody(JsonNode body) {
        return verdict(body.path("output"), body.path("violations"));
    }

    private static String verdict(JsonNode output, JsonNode violations) {
        ObjectNode verdict = NODES.objectNode();
        verdict.set("output", output == null || output.isMissingNode() ? NODES.nullNode() : output);
        verdict.set("violations", violations == null || violations.isMissingNode() ? NODES.nullNode() : violations);
        return canonical(verdict);
    }

    /** Keys sorted at every depth, compact. */
    static String canonical(JsonNode node) {
        return sorted(node).toString();
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                fields.put(field.getKey(), sorted(field.getValue()));
            }
            ObjectNode copy = NODES.objectNode();
            fields.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode();
            node.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }

    // ------------------------------------------------------------------ the parity assertion

    /**
     * Every sample's panel verdict equals its runtime verdict AND the committed expected verdict - 20 of 20, no
     * tolerance, no subset. Fails with every disagreement listed.
     *
     * @return the report line (for the run log)
     */
    static String assertSameAnswer(List<Sample> samples, Function<Sample, String> panel,
            Function<Sample, String> runtime, Map<String, String> expected) {
        assertThat(samples).as("the parity fixture holds exactly %d samples", SAMPLE_COUNT).hasSize(SAMPLE_COUNT);
        assertThat(expected.keySet()).as("every sample has exactly one expected verdict")
                .containsExactlyElementsOf(samples.stream().map(Sample::id).toList());

        int panelEqualsRuntime = 0;
        int runtimeEqualsExpected = 0;
        List<String> disagreements = new ArrayList<>();
        for (Sample sample : samples) {
            String fromPanel = panel.apply(sample);
            String fromRuntime = runtime.apply(sample);
            String wanted = expected.get(sample.id());
            boolean same = fromPanel.equals(fromRuntime);
            boolean asExpected = fromRuntime.equals(wanted);
            panelEqualsRuntime += same ? 1 : 0;
            runtimeEqualsExpected += asExpected ? 1 : 0;
            if (!same || !asExpected) {
                disagreements.add(sample.id() + "\n    panel    " + fromPanel + "\n    runtime  " + fromRuntime
                        + "\n    expected " + wanted);
            }
        }
        String report = "panel == runtime: " + panelEqualsRuntime + " of " + samples.size()
                + "; runtime == expected-verdicts.json: " + runtimeEqualsExpected + " of " + samples.size();
        assertThat(disagreements).as("%s; disagreements:%n%s", report, String.join("\n", disagreements)).isEmpty();
        return report;
    }

    /** The fixture is not trivially empty: samples with 0, 1 and several violations, and with routing outputs. */
    static void assertCoverage(Map<String, String> expected) {
        int none = 0;
        int one = 0;
        int several = 0;
        int routed = 0;
        for (String verdict : expected.values()) {
            JsonNode node = JSON.readTree(verdict);
            int violations = node.path("violations").size();
            none += violations == 0 ? 1 : 0;
            one += violations == 1 ? 1 : 0;
            several += violations > 1 ? 1 : 0;
            routed += node.path("output").findValues("route").isEmpty() ? 0 : 1;
        }
        assertThat(none).as("samples with no violation").isGreaterThanOrEqualTo(3);
        assertThat(one).as("samples with exactly one violation").isGreaterThanOrEqualTo(3);
        assertThat(several).as("samples with several violations").isGreaterThanOrEqualTo(3);
        assertThat(routed).as("samples with a routing output").isGreaterThanOrEqualTo(3);
    }
}
