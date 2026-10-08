package io.integrationplatform.config.rule.application;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The decision engine behind the rules (ADR-003: GoRules ZEN). The application talks to this interface only; the one
 * implementation lives in {@code rule/adapter/out}, so no other package of the module touches an engine class.
 */
public interface DecisionEngine {

    /**
     * The save-time check of a JSON Decision Model, with the engine's own parsers: the first problem found, or empty
     * when the engine accepts every table and every cell.
     */
    Optional<TableProblem> check(String jdm);

    /** Evaluates a decision model (already checked) on one input; the result exactly as the engine returns it. */
    JsonNode evaluate(String jdm, JsonNode input);

    /**
     * Where a decision model is malformed and why.
     *
     * @param node   the decision node's id, or null when the model as a whole is unreadable
     * @param row    the zero-based index into that node's {@code rules}, or null for a column-level problem
     * @param column the input or output column's id, or null
     * @param reason the engine's reason (or the column rule's), never empty
     */
    record TableProblem(String node, Integer row, String column, String reason) {
    }
}
