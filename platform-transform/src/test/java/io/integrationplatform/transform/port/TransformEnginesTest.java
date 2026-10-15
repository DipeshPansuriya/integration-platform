package io.integrationplatform.transform.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformEngines;
import io.integrationplatform.transform.api.TransformPort;
import org.junit.jupiter.api.Test;

/**
 * The public way other modules obtain the engine (row 278 E7 B2 follow-up): {@code TransformEngines.jsonata(budgetMs)}
 * hands out the ADR-006 engine as a {@link TransformPort} only, so no module outside transform names
 * {@code transform.internal} (ModuleInternalsRule).
 */
class TransformEnginesTest {

    private static final LookupSource NO_LOOKUPS = (table, key) -> null;

    @Test
    void theJsonataEngineAnswersThroughThePortOnly() {
        TransformPort port = TransformEngines.jsonata(5_000L);

        TransformPort.Result result = port.transform("{\"total\": a + b}", "{\"a\": 2, \"b\": 3}", NO_LOOKUPS);

        assertThat(result.status()).isEqualTo(TransformPort.Status.OK);
        assertThat(result.outputJson()).isEqualTo("{\"total\":5}");
    }

    @Test
    void theBudgetIsTheCallersFigureAndMustBePositive() {
        assertThatThrownBy(() -> TransformEngines.jsonata(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
    }
}
