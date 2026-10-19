package io.integrationplatform.ingest.internal.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T115 pure logic: the request hash (T-187) and the ordering plan (T-196). */
class IntakeKeysTest {

    @Test
    void sameRequestHashesEqualAndAnyChangeDiffers() {
        String a = IntakeKeys.requestHash("ORDERS", "1.0", List.of("{\"id\":1}", "{\"id\":2}"));
        assertThat(a).hasSize(64).isEqualTo(IntakeKeys.requestHash("ORDERS", "1.0", List.of("{\"id\":1}", "{\"id\":2}")));
        assertThat(IntakeKeys.requestHash("ORDERS", "1.0", List.of("{\"id\":2}", "{\"id\":1}"))).isNotEqualTo(a);
        assertThat(IntakeKeys.requestHash("ORDERS", "1.1", List.of("{\"id\":1}", "{\"id\":2}"))).isNotEqualTo(a);
        assertThat(IntakeKeys.requestHash("ORDERX", "1.0", List.of("{\"id\":1}", "{\"id\":2}"))).isNotEqualTo(a);
        // no separator collision: ["ab","c"] vs ["a","bc"]
        assertThat(IntakeKeys.requestHash("I", "1", List.of("ab", "c")))
                .isNotEqualTo(IntakeKeys.requestHash("I", "1", List.of("a", "bc")));
    }

    @Test
    void orderingPlanIsDistinctKeysInKeyHashOrderWithCounts() {
        Map<String, Integer> plan = IntakeKeys.orderingPlan(List.of("k3", "k1", "k3", "k2", "k1", "k3"));
        assertThat(plan).containsEntry("k1", 2).containsEntry("k2", 1).containsEntry("k3", 3).hasSize(3);
        List<String> expected = new ArrayList<>(List.of("k1", "k2", "k3"));
        expected.sort((x, y) -> IntakeKeys.sha256(x).compareTo(IntakeKeys.sha256(y)));
        assertThat(new ArrayList<>(plan.keySet())).isEqualTo(expected);
        // the same key set always locks in the same order, whatever the arrival order
        assertThat(new ArrayList<>(IntakeKeys.orderingPlan(List.of("k2", "k3", "k1")).keySet())).isEqualTo(expected);
    }

    @Test
    void orderingIsSkippedWhenNone() {
        assertThat(IntakeKeys.ordered("NONE")).isFalse();
        assertThat(IntakeKeys.ordered("PER_KEY")).isTrue();
        assertThat(IntakeKeys.ordered(null)).isTrue();
    }
}
