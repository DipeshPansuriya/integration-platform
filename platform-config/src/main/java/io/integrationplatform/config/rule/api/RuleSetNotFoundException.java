package io.integrationplatform.config.rule.api;

/** The rule set is not visible to the bound tenant (another tenant's, or none); the message names no id. */
public class RuleSetNotFoundException extends RuntimeException {

    public RuleSetNotFoundException() {
        super("rule set not found");
    }
}
