package io.integrationplatform.ingest.validation;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.integrationplatform.ingest.validation.ContractSchemaCheck.SchemaCheck;
import io.integrationplatform.ingest.validation.Violation.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * T-027 (E6-F2-S1, ADR-003 rule 4, architecture.md stage 3): validates one payload and lists EVERY violation in one
 * {@link RejectionEnvelope} - contract validation against the registered JSON Schema first, then each VALIDATION rule
 * set of the interface through the configuration module's {@link RuleEvaluationPort}, in the order given (the
 * interface's rule-set order; inside a set, table order).
 *
 * <p>The rules run on a payload that is a JSON object of the contract's type even when it has field-level schema
 * errors, so both steps' violations come back together; they are skipped ({@code rulesEvaluated = false}) only when
 * the payload is not such an object.
 *
 * <p>Application-service level: the caller (the common intake API, a later epic) resolves the message's contract
 * schema and the interface's VALIDATION rule set ids, and calls this inside its tenant-stamped transaction (R11) - the
 * port evaluates the bound tenant's rule sets only.
 */
public class PayloadValidationService {

    private final ContractSchemaCheck schemaCheck;
    private final RuleEvaluationPort rules;

    public PayloadValidationService(ContractSchemaCheck schemaCheck, RuleEvaluationPort rules) {
        this.schemaCheck = Objects.requireNonNull(schemaCheck, "schemaCheck");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    /**
     * @param contractSchema         the registered JSON Schema of the message's contract version
     * @param validationRuleSetIds   the interface's VALIDATION rule sets, in order
     * @param payload                the record to validate
     */
    public RejectionEnvelope validate(JsonNode contractSchema, List<UUID> validationRuleSetIds, JsonNode payload) {
        SchemaCheck schema = schemaCheck.check(contractSchema, payload);
        List<Violation> violations = new ArrayList<>(schema.violations());
        if (!schema.contractShaped()) {
            return new RejectionEnvelope(violations, false);
        }
        for (UUID ruleSetId : validationRuleSetIds) {
            for (RuleViolation violation : rules.evaluate(ruleSetId, payload).violations()) {
                violations.add(new Violation(violation.field(), violation.code(), violation.message(), Source.RULE));
            }
        }
        return new RejectionEnvelope(violations, true);
    }
}
