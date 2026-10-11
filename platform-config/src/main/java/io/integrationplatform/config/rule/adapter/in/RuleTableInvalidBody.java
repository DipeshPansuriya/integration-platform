package io.integrationplatform.config.rule.adapter.in;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation only: the 400 answer of the rule-set writes as {@code RuleTableInvalidException} builds it, so the
 * console's typed client knows the refused cell. A plain validation refusal (kind, name) carries {@code error} only.
 */
@Schema(name = "RuleTableInvalidBody",
        description = "RULE_TABLE_INVALID: the refused cell and the engine's reason; or {error} for a validation refusal.")
public record RuleTableInvalidBody(
        @Schema(description = "RULE_TABLE_INVALID, or the validation message") String error,
        @Schema(description = "The refused cell; null members when the model as a whole is unreadable") Cell cell,
        @Schema(description = "Why the table is refused (the engine's own reason)") String reason) {

    /**
     * @param node   the decision node's id
     * @param row    zero-based index into that node's rules; null for a column-level problem
     * @param column the input or output column's id
     */
    @Schema(name = "RuleTableCell")
    public record Cell(String node, Integer row, String column) {
    }
}
