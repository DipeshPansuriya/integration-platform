package io.integrationplatform.config.rule.adapter.out;

import io.gorules.zen_engine.JsonBuffer;
import io.gorules.zen_engine.ZenDecision;
import io.gorules.zen_engine.ZenEngine;
import io.gorules.zen_engine.ZenException;
import io.gorules.zen_engine.ZenExpression;
import io.gorules.zen_engine.ZenExpressionUnary;
import io.integrationplatform.config.rule.application.DecisionEngine;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The GoRules ZEN engine (io.gorules:zen-engine 2.1.1, MIT, ADR-003) behind {@link DecisionEngine} - the only class
 * of the module that imports an engine class. The engine runs in-process (a native library bound through
 * java.lang.foreign), so no network call leaves the process here.
 *
 * <p>The save-time check uses the engine's own parsers, because the engine does NOT refuse an unparsable cell when a
 * decision is created or validated: at runtime such a row silently never matches. So every non-empty cell of every
 * decision table is compiled - an input cell under a named field as a unary expression, every other cell as a
 * standard expression - and the first failure names its node, row and column with the engine's reason. A condition
 * column that names no field and holds no condition in any row is empty, and refused too.
 */
public final class ZenDecisionEngine implements DecisionEngine, AutoCloseable {

    /** An upper bound on one in-process evaluation; a decision table answers in microseconds. */
    private static final long EVALUATION_TIMEOUT_SECONDS = 5;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ZenEngine engine;

    public ZenDecisionEngine() {
        try {
            this.engine = new ZenEngine(null, null);
        } catch (ZenException e) {
            throw new IllegalStateException("the ZEN engine could not start: " + reason(e), e);
        }
    }

    @Override
    public Optional<TableProblem> check(String jdm) {
        try (ZenDecision decision = engine.createDecision(new JsonBuffer(jdm))) {
            decision.validate();
        } catch (ZenException e) {
            return Optional.of(new TableProblem(null, null, null, reason(e)));
        }
        for (JsonNode node : JSON.readTree(jdm).path("nodes")) {
            if ("decisionTableNode".equals(node.path("type").asString())) {
                Optional<TableProblem> problem = checkTable(node.path("id").asString(), node.path("content"));
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public JsonNode evaluate(String jdm, JsonNode input) {
        try (ZenDecision decision = engine.createDecision(new JsonBuffer(jdm))) {
            JsonBuffer result = decision.evaluate(new JsonBuffer(input.toString()), null)
                    .get(EVALUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .result();
            return JSON.readTree(result.value());
        } catch (ZenException e) {
            throw new IllegalStateException("the rule set could not be evaluated: " + reason(e), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("the rule set evaluation failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the rule set evaluation was interrupted", e);
        }
    }

    @Override
    public void close() {
        engine.close();
    }

    private static Optional<TableProblem> checkTable(String nodeId, JsonNode table) {
        JsonNode rules = table.path("rules");
        for (JsonNode input : table.path("inputs")) {
            String column = input.path("id").asString();
            boolean namesField = !input.path("field").asString("").isBlank();
            boolean holdsCondition = false;
            for (JsonNode rule : rules) {
                holdsCondition |= !rule.path(column).asString("").isBlank();
            }
            if (!namesField && !holdsCondition) {
                return Optional.of(new TableProblem(nodeId, null, column,
                        "the condition column is empty: it names no input field and no row holds a condition"));
            }
        }
        int row = 0;
        for (JsonNode rule : rules) {
            for (JsonNode input : table.path("inputs")) {
                boolean unary = !input.path("field").asString("").isBlank();
                Optional<TableProblem> problem = compile(nodeId, row, input.path("id").asString(), rule, unary);
                if (problem.isPresent()) {
                    return problem;
                }
            }
            for (JsonNode output : table.path("outputs")) {
                Optional<TableProblem> problem = compile(nodeId, row, output.path("id").asString(), rule, false);
                if (problem.isPresent()) {
                    return problem;
                }
            }
            row++;
        }
        return Optional.empty();
    }

    private static Optional<TableProblem> compile(String nodeId, int row, String column, JsonNode rule,
            boolean unary) {
        String expression = rule.path(column).asString("");
        if (expression.isBlank()) {
            return Optional.empty(); // an empty cell matches anything (input) or outputs nothing (output)
        }
        try {
            if (unary) {
                ZenExpressionUnary.compile(expression).close();
            } else {
                ZenExpression.compile(expression).close();
            }
            return Optional.empty();
        } catch (ZenException e) {
            return Optional.of(new TableProblem(nodeId, row, column, reason(e)));
        }
    }

    /** The engine's own message: its exceptions carry it as "v1=..." (IsolateException, ValidationException). */
    private static String reason(ZenException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "the engine refused the decision model (" + e.getClass().getSimpleName() + ")";
        }
        return message.startsWith("v1=") ? message.substring(3) : message;
    }
}
