package io.integrationplatform.ingest.validation;

import java.util.Objects;

/**
 * One problem with a payload (E6-F2-S1): where it is, a stable code, a readable message, and which step found it.
 *
 * @param field   the field path in the payload - dotted names and {@code [index]} for array items, {@code $} for the
 *                payload root (schema) or the field a rule names (rules)
 * @param code    {@code SCHEMA_<KEYWORD>} for a schema violation (for example {@code SCHEMA_REQUIRED}), the rule row's
 *                code for a rule violation
 * @param message the validator's or the rule row's message
 * @param source  {@link Source#SCHEMA} (the registered contract) or {@link Source#RULE} (a VALIDATION rule set)
 */
public record Violation(String field, String code, String message, Source source) {

    /** The step that found a violation: contract validation runs first, the rules after it (ADR-003 rule 4). */
    public enum Source {
        SCHEMA,
        RULE
    }

    public Violation {
        Objects.requireNonNull(source, "source");
    }
}
