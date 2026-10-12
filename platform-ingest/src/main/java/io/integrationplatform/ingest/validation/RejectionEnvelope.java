package io.integrationplatform.ingest.validation;

import java.util.List;

/**
 * The outcome of validating one payload (E6-F2-S1, G2): every violation in one answer - schema violations first, then
 * rule violations in rule-set and table order - never first-error-only.
 *
 * @param violations     all violations found; empty when the payload passed both steps
 * @param rulesEvaluated {@code false} only when the payload was not a JSON object of the contract's type, so the rules
 *                       were not run (ADR-003 rule 4: rules see only structurally valid payloads)
 */
public record RejectionEnvelope(List<Violation> violations, boolean rulesEvaluated) {

    public RejectionEnvelope {
        violations = List.copyOf(violations);
    }

    /** {@code true} when the payload has at least one violation and must be rejected. */
    public boolean rejected() {
        return !violations.isEmpty();
    }
}
