package io.integrationplatform.ingest.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.ExecutionConfig;
import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.integrationplatform.ingest.validation.Violation.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E6-F2-S1 (T-027, ADR-003 rule 4, architecture.md stage 3): every violation of a payload in one rejection - contract
 * validation against the registered JSON Schema first (networknt 2.0.1, collecting every error, never fail-fast),
 * then each VALIDATION rule set of the interface through {@link RuleEvaluationPort}, in table order. Rules are skipped
 * only when the payload is not a JSON object of the contract's type.
 *
 * <pre>
 * Scenario: Schema first, then rules, all listed
 * &#64;negative Scenario: Never first-error-only
 * &#64;negative Scenario: Rules do not run on a payload that is not the contract's shape
 * </pre>
 */
class PayloadValidationServiceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** The registered contract (draft 2020-12) every scenario validates against. */
    static final String CONTRACT = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object",
             "required":["orderNo","amount","currency","reference"],
             "properties":{
               "orderNo":{"type":"string","maxLength":10},
               "amount":{"type":"number"},
               "currency":{"type":"string","minLength":3,"maxLength":3},
               "reference":{"type":"string"},
               "quantity":{"type":"integer","minimum":1},
               "customer":{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}},
               "lines":{"type":"array","items":{"type":"object","required":["sku"],
                        "properties":{"sku":{"type":"string"},"qty":{"type":"integer"}}}}}}
            """;

    static final UUID AMOUNT_RULES = UUID.fromString("a0000000-0000-4000-8000-000000000027");
    static final UUID REFERENCE_RULES = UUID.fromString("b0000000-0000-4000-8000-000000000027");

    // ------------------------------------------------------------ Scenario: Schema first, then rules, all listed

    @Test
    @DisplayName("Scenario: Schema first, then rules, all listed - 2 schema + 3 rule violations in one rejection")
    void schemaFirstThenRulesAllListed() {
        // Given a payload with two JSON Schema violations (orderNo missing, quantity not an integer) ...
        JsonNode payload = json("{\"amount\":150,\"currency\":\"XXX\",\"reference\":\"\",\"quantity\":\"two\"}");
        // ... and three rule violations (two from the first VALIDATION rule set, one from the second)
        List<String> log = new ArrayList<>();
        RecordingRules rules = new RecordingRules(log, Map.of(
                AMOUNT_RULES, List.of(new RuleViolation("amount", "AMOUNT_TOO_HIGH", "amount must not exceed 100"),
                        new RuleViolation("currency", "CURRENCY_UNKNOWN", "currency XXX is not accepted")),
                REFERENCE_RULES, List.of(new RuleViolation("reference", "REFERENCE_REQUIRED", "reference is required"))));
        PayloadValidationService service = new PayloadValidationService(new RecordingSchemaCheck(log), rules);

        // When it is validated
        RejectionEnvelope rejection = service.validate(json(CONTRACT), List.of(AMOUNT_RULES, REFERENCE_RULES), payload);

        // Then contract validation runs before the rules: the schema step completed before the first evaluate call
        assertThat(log).containsExactly("schema:done", "evaluate:" + AMOUNT_RULES, "evaluate:" + REFERENCE_RULES);
        // And one rejection lists all five violations, each with its field path, code and message
        assertThat(rejection.rejected()).isTrue();
        assertThat(rejection.rulesEvaluated()).isTrue();
        assertThat(rejection.violations()).hasSize(5);
        assertThat(rejection.violations()).allSatisfy(violation -> {
            assertThat(violation.field()).isNotBlank();
            assertThat(violation.code()).isNotBlank();
            assertThat(violation.message()).isNotBlank();
        });
        assertThat(rejection.violations().subList(0, 2)).extracting(Violation::source).containsOnly(Source.SCHEMA);
        assertThat(rejection.violations().subList(0, 2)).extracting(Violation::field, Violation::code)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("orderNo", "SCHEMA_REQUIRED"),
                        org.assertj.core.groups.Tuple.tuple("quantity", "SCHEMA_TYPE"));
        assertThat(rejection.violations().subList(2, 5)).extracting(Violation::source).containsOnly(Source.RULE);
        assertThat(rejection.violations().subList(2, 5)).extracting(Violation::code)
                .as("rule violations keep rule-set order, then table order")
                .containsExactly("AMOUNT_TOO_HIGH", "CURRENCY_UNKNOWN", "REFERENCE_REQUIRED");
        assertThat(rejection.violations().get(2).field()).isEqualTo("amount");
        assertThat(rejection.violations().get(2).message()).isEqualTo("amount must not exceed 100");
    }

    @Test
    @DisplayName("Nested field paths: a missing required property names its full path, an array item its index")
    void nestedFieldPathsAreNamed() {
        RejectionEnvelope rejection = collectingService(Map.of()).validate(json(CONTRACT), List.of(), json("""
                {"orderNo":"1","amount":1,"currency":"EUR","reference":"r","customer":{},"lines":[{"qty":"a"}]}
                """));

        assertThat(rejection.violations()).extracting(Violation::field, Violation::code).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("customer.name", "SCHEMA_REQUIRED"),
                org.assertj.core.groups.Tuple.tuple("lines[0].sku", "SCHEMA_REQUIRED"),
                org.assertj.core.groups.Tuple.tuple("lines[0].qty", "SCHEMA_TYPE"));
    }

    @Test
    @DisplayName("A valid payload with no rule hits is not a rejection; the rules were evaluated")
    void aCleanPayloadIsNotRejected() {
        RejectionEnvelope result = collectingService(Map.of(AMOUNT_RULES, List.of())).validate(json(CONTRACT),
                List.of(AMOUNT_RULES), json("{\"orderNo\":\"1\",\"amount\":1,\"currency\":\"EUR\",\"reference\":\"r\"}"));

        assertThat(result.rejected()).isFalse();
        assertThat(result.violations()).isEmpty();
        assertThat(result.rulesEvaluated()).isTrue();
    }

    // ------------------------------------------------------------ @negative Scenario: Never first-error-only

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: Never first-error-only - ten violations listed, not one")
    void neverFirstErrorOnly() {
        assertTenViolationsListed(new ContractSchemaCheck());
    }

    @Test
    @Tag("negative")
    @DisplayName("R02 planted violation: a fail-fast validator configuration turns the ten-violation check red")
    void aFailFastValidatorIsCaught() {
        ContractSchemaCheck planted = new ContractSchemaCheck(ExecutionConfig.builder().failFast(true).build());

        assertThatThrownBy(() -> assertTenViolationsListed(planted))
                .as("the ten-violation scenario must fail on a fail-fast (first-error-only) validator");
    }

    /** Given a payload with ten violations (8 schema, 2 rule) - When it is validated - Then ten are listed. */
    private static void assertTenViolationsListed(ContractSchemaCheck schemaCheck) {
        JsonNode payload = json("""
                {"orderNo":"ABCDEFGHIJKLMNOP","amount":"x","currency":"EURO","quantity":0,
                 "customer":{},"lines":[{"qty":"a"}]}
                """);
        RecordingRules rules = new RecordingRules(new ArrayList<>(), Map.of(AMOUNT_RULES, List.of(
                new RuleViolation("amount", "AMOUNT_NOT_NUMERIC", "amount must be numeric"),
                new RuleViolation("quantity", "QUANTITY_TOO_LOW", "quantity must be at least 1"))));

        RejectionEnvelope rejection = new PayloadValidationService(schemaCheck, rules)
                .validate(json(CONTRACT), List.of(AMOUNT_RULES), payload);

        assertThat(rejection.violations()).as("ten violations, not one").hasSize(10);
        assertThat(rejection.violations()).filteredOn(v -> v.source() == Source.SCHEMA).hasSize(8);
        assertThat(rejection.violations()).filteredOn(v -> v.source() == Source.RULE).hasSize(2);
    }

    // ------------------------------------------- @negative Scenario: Rules do not run on a payload of another shape

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: Rules do not run on a payload that is not the contract's shape (a JSON array)")
    void rulesDoNotRunOnAnArray() {
        List<String> log = new ArrayList<>();
        RecordingRules rules = new RecordingRules(log, Map.of(AMOUNT_RULES, List.of(
                new RuleViolation("amount", "AMOUNT_TOO_HIGH", "amount must not exceed 100"))));

        RejectionEnvelope rejection = new PayloadValidationService(new ContractSchemaCheck(), rules)
                .validate(json(CONTRACT), List.of(AMOUNT_RULES), json("[{\"orderNo\":\"1\"}]"));

        assertThat(rejection.rejected()).isTrue();
        assertThat(rejection.rulesEvaluated()).as("states that the rules were not evaluated").isFalse();
        assertThat(rejection.violations()).isNotEmpty().allSatisfy(v -> assertThat(v.source()).isEqualTo(Source.SCHEMA));
        assertThat(rejection.violations()).extracting(Violation::field, Violation::code)
                .contains(org.assertj.core.groups.Tuple.tuple("$", "SCHEMA_TYPE"));
        assertThat(log).as("RuleEvaluationPort is never called").isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative A scalar or null payload is not the contract's shape either: schema errors, no rules")
    void rulesDoNotRunOnAScalar() {
        for (String payload : List.of("\"an order\"", "42", "null")) {
            List<String> log = new ArrayList<>();
            RejectionEnvelope rejection = new PayloadValidationService(new ContractSchemaCheck(),
                    new RecordingRules(log, Map.of())).validate(json(CONTRACT), List.of(AMOUNT_RULES), json(payload));

            assertThat(rejection.rulesEvaluated()).as(payload).isFalse();
            assertThat(rejection.violations()).as(payload).isNotEmpty();
            assertThat(log).as(payload).isEmpty();
        }
    }

    // ------------------------------------------------------------------ doubles and helpers

    private static PayloadValidationService collectingService(Map<UUID, List<RuleViolation>> violations) {
        return new PayloadValidationService(new ContractSchemaCheck(), new RecordingRules(new ArrayList<>(), violations));
    }

    static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    /** The schema step, recording the moment it completes. */
    static final class RecordingSchemaCheck extends ContractSchemaCheck {

        private final List<String> log;

        RecordingSchemaCheck(List<String> log) {
            this.log = log;
        }

        @Override
        public SchemaCheck check(JsonNode contractSchema, JsonNode payload) {
            SchemaCheck result = super.check(contractSchema, payload);
            log.add("schema:done");
            return result;
        }
    }

    /** A recording test double of the port: logs each evaluate call and answers the canned violations. */
    static final class RecordingRules implements RuleEvaluationPort {

        private final List<String> log;
        private final Map<UUID, List<RuleViolation>> violations;

        RecordingRules(List<String> log, Map<UUID, List<RuleViolation>> violations) {
            this.log = log;
            this.violations = violations;
        }

        @Override
        public RuleEvaluation evaluate(UUID ruleSetId, JsonNode input) {
            log.add("evaluate:" + ruleSetId);
            return new RuleEvaluation(ruleSetId, "VALIDATION", JSON.createArrayNode(),
                    violations.getOrDefault(ruleSetId, List.of()));
        }
    }
}
