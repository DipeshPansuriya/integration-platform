package io.integrationplatform.config.rule.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.Jdm;
import io.integrationplatform.config.rule.application.DecisionEngine.TableProblem;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-024's riskiest step first: io.gorules:zen-engine 2.1.1's native library loads on this PC in a BARE unit test (no
 * container, no Spring), and the save-time table check uses the engine's own parsers - the engine silently treats an
 * unparsable cell as a non-matching row at runtime, so the editor and the runtime agree only if the save refuses it.
 */
class ZenDecisionEngineTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ZenDecisionEngine engine;

    @BeforeAll
    static void loadTheNativeEngine() {
        engine = new ZenDecisionEngine();
    }

    @AfterAll
    static void close() {
        engine.close();
    }

    @Test
    @DisplayName("The native ZEN library loads without a container and evaluates a decision table")
    void nativeLibraryLoadsAndEvaluates() {
        JsonNode output = engine.evaluate(Jdm.routingTable(), JSON.readTree("{\"country\":\"NL\"}"));

        assertThat(output.path("target").asString()).isEqualTo("dst-eu");
    }

    @Test
    @DisplayName("A collect-policy table returns every matching row, in table order")
    void collectReturnsEveryMatchingRowInOrder() {
        JsonNode output = engine.evaluate(Jdm.validationTable(), JSON.readTree(Jdm.validationInput()));

        assertThat(output.findValuesAsString("code"))
                .containsExactly("Z_AMOUNT_TOO_HIGH", "A_CURRENCY_UNKNOWN", "M_REFERENCE_REQUIRED");
    }

    @Test
    @DisplayName("Well-formed tables of every kind pass the check")
    void wellFormedTablesPass() {
        assertThat(engine.check(Jdm.validationTable())).isEmpty();
        assertThat(engine.check(Jdm.routingTable())).isEmpty();
        assertThat(engine.check(Jdm.lookupTable())).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("An unparsable expression is refused with its node, row and column and the engine's reason")
    void unparsableExpressionIsRefusedWithItsCell() {
        Optional<TableProblem> problem = engine.check(Jdm.unparsableExpression());

        assertThat(problem).isPresent();
        assertThat(problem.get().node()).isEqualTo(Jdm.TABLE_NODE);
        assertThat(problem.get().row()).isEqualTo(1);
        assertThat(problem.get().column()).isEqualTo("c1");
        assertThat(problem.get().reason()).containsIgnoringCase("parser");
    }

    @Test
    @Tag("negative")
    @DisplayName("An empty condition column (no field, no condition in any row) is refused with its column")
    void emptyConditionColumnIsRefused() {
        Optional<TableProblem> problem = engine.check(Jdm.emptyConditionColumn());

        assertThat(problem).isPresent();
        assertThat(problem.get().node()).isEqualTo(Jdm.TABLE_NODE);
        assertThat(problem.get().row()).isNull();
        assertThat(problem.get().column()).isEqualTo("c9");
        assertThat(problem.get().reason()).isNotBlank();
    }

    @Test
    @Tag("negative")
    @DisplayName("A content the engine cannot read as a decision model is refused, with no cell")
    void notADecisionModelIsRefused() {
        Optional<TableProblem> problem = engine.check("{\"nodes\":\"not a list\"}");

        assertThat(problem).isPresent();
        assertThat(problem.get().node()).isNull();
        assertThat(problem.get().reason()).isNotBlank();
    }
}
