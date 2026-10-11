package io.integrationplatform.config.rule.api;

/**
 * One violation a VALIDATION rule set reports. The convention (lane A depends on it): the decision table uses
 * hitPolicy "collect" and each matching row outputs {@code violation.field}, {@code violation.code} and
 * {@code violation.message}.
 */
public record RuleViolation(String field, String code, String message) {
}
