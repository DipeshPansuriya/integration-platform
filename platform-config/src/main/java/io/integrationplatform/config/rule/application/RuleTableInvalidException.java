package io.integrationplatform.config.rule.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.rule.application.DecisionEngine.TableProblem;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 400 for a malformed decision table: {@code {"error":"RULE_TABLE_INVALID","cell":{"node","row","column"},"reason"}}
 * (E6-F1-S1, "refused with the cell and the reason"). It is a {@link ConfigApiException}, so the configuration API's
 * one error seam answers it like every other config refusal.
 */
public class RuleTableInvalidException extends ConfigApiException {

    public static final String ERROR = "RULE_TABLE_INVALID";

    public RuleTableInvalidException(TableProblem problem) {
        super(400, body(problem));
    }

    private static Map<String, Object> body(TableProblem problem) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("node", problem.node());
        cell.put("row", problem.row());
        cell.put("column", problem.column());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ERROR);
        body.put("cell", cell);
        body.put("reason", problem.reason());
        return body;
    }
}
