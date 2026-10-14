package io.integrationplatform.transform.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import io.integrationplatform.transform.internal.DashjoinJsonataTransformPort;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minidev.json.JSONValue;
import org.junit.jupiter.api.Test;

/**
 * T-108 (T-53, E7 wave-1 lane B2) - the {@link TransformPort} contract on the real ADR-006 engine:
 * the five ADR-006 breaking cases run through the port (not a spike harness) with lookups resolved
 * ONLY through the {@link LookupSource} seam, plus T-109's runaway case: an expression that does not
 * finish stops at the budget with {@link TransformPort.Status#MAPPING_TIMEOUT} and a transform after
 * it is unaffected (backlog.md:1896-1899).
 *
 * <p>The five cases and the reference payload are lane A's (amit-developer, spike branch T-030/T-031,
 * commit e39d24f: reference payload, expected outputs and the lookup tables of
 * {@code SpikeCandidate.referenceTables()}); they are embedded here because the spike branch is test
 * scope and not merged to main - the port must pass the same five cases the ADR was proved with.</p>
 *
 * <p>Engine boundary (ADR-006): the engine carries no Jackson; input is parsed at the string boundary,
 * lookups enter as strings, the output leaves as a JSON string.</p>
 */
class TransformPortContractTest {

    /** Lane A's reference payload (SpikeCase, spike branch e39d24f). */
    private static final String CUSTOMER_A =
            "{\"id\":\"O-1001\",\"customer\":{\"ref\":\"C-42\",\"name\":\"Amit\"},\"category\":\"b2b\","
            + "\"code\":\"S2\",\"items\":[{\"sku\":\"A1\",\"qty\":2,\"price\":10.0},"
            + "{\"sku\":\"B2\",\"qty\":1,\"price\":5.5}]}";

    /** The seam stub: the three reference tables, one row per call, counting the calls it is asked. */
    private static final class ReferenceTables implements LookupSource {

        final AtomicLong calls = new AtomicLong();

        @Override
        public String entry(String tableName, String key) {
            calls.incrementAndGet();
            return switch (tableName + "/" + String.valueOf(key)) {
                case "order_status/S1" -> "Shipped";
                case "order_status/S2" -> "Pending";
                case "order_status/S9" -> null;
                case "customer_tier/C-42" -> "gold";
                case "customer_profile_name/C-42" -> "Amit Bhandari";
                default -> null;
            };
        }
    }

    private static TransformPort port() {
        return new DashjoinJsonataTransformPort(5000);
    }

    /** Canonical comparison at the JSON boundary: keys sorted, arrays in order, numbers plain (20 = 20.0). */
    private static String canonical(String json) {
        return canonicalValue(JSONValue.parse(json));
    }

    @SuppressWarnings("unchecked")
    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder inner = new StringBuilder();
            new TreeMap<String, Object>((Map<String, Object>) map).forEach((key, sub) -> inner
                    .append(inner.isEmpty() ? "" : ", ").append(key).append(": ").append(canonicalValue(sub)));
            return "{" + inner + "}";
        }
        if (value instanceof Iterable<?> list) {
            StringBuilder inner = new StringBuilder();
            for (Object item : list) {
                inner.append(inner.isEmpty() ? "" : ", ").append(canonicalValue(item));
            }
            return "[" + inner + "]";
        }
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    // ---------------------------------------------------------------- the five ADR-006 breaking cases

    // Scenario: LOOKUPS - the mapped status value comes from the LookupSource seam, not from the expression.
    @Test
    void theMappedStatusValueComesFromTheLookupSourceSeam() {
        ReferenceTables lookups = new ReferenceTables();
        String expression = "{\"id\": id, \"status\": $lookup(\"order_status\", code)}";

        TransformPort.Result result = port().transform(expression, CUSTOMER_A, lookups);

        assertNotNull(result, "the port never returns null");
        assertTrue(TransformPort.Status.OK == result.status(), () -> result.toString());
        assertEquals(canonical("{\"id\": \"O-1001\", \"status\": \"Pending\"}"), canonical(result.outputJson()),
                () -> result.toString());
        assertTrue(lookups.calls.get() >= 1, "the lookup went through the seam");
    }

    // Scenario: CONDITIONALS - one branch per category value, decided at runtime.
    @Test
    void theConditionalPicksTheB2bPartnerBranch() {
        String expression = "{\"id\": id, \"channel\": category = \"b2b\" ? \"partner\" : \"direct\"}";

        TransformPort.Result result = port().transform(expression, CUSTOMER_A, new ReferenceTables());

        assertTrue(TransformPort.Status.OK == result.status(), () -> result.toString());
        assertEquals(canonical("{\"id\": \"O-1001\", \"channel\": \"partner\"}"), canonical(result.outputJson()));
    }

    // Scenario: ARRAY_RESTRUCTURING - one output line per input item, per-element arithmetic, aggregate gross.
    @Test
    void theArrayRestructuresWithPerElementTotalsAndTheGross() {
        String expression = "{\"orderId\": id, \"lines\": items.{\"sku\": sku, \"total\": qty * price}, "
                + "\"gross\": $sum($map(items, function($i) { $i.qty * $i.price }))}";

        TransformPort.Result result = port().transform(expression, CUSTOMER_A, new ReferenceTables());

        assertTrue(TransformPort.Status.OK == result.status(), () -> result.toString());
        assertEquals(canonical("{\"orderId\": \"O-1001\", \"lines\": [{\"sku\": \"A1\", \"total\": 20}, "
                        + "{\"sku\": \"B2\", \"total\": 5.5}], \"gross\": 25.5}"),
                canonical(result.outputJson()));
    }

    // Scenario: ENRICHMENT - two lookup rows keyed by customer.ref joined into the output.
    @Test
    void theEnrichmentJoinsTwoLookupRows() {
        String expression = "{\"id\": id, \"tier\": $lookup(\"customer_tier\", customer.ref), "
                + "\"displayName\": $lookup(\"customer_profile_name\", customer.ref)}";

        TransformPort.Result result = port().transform(expression, CUSTOMER_A, new ReferenceTables());

        assertTrue(TransformPort.Status.OK == result.status(), () -> result.toString());
        assertEquals(canonical("{\"id\": \"O-1001\", \"tier\": \"gold\", \"displayName\": \"Amit Bhandari\"}"),
                canonical(result.outputJson()));
    }

    // @negative Scenario: ERROR_TRANSLATION - a describable failure, never a silent wrong answer.
    @Test
    void anArithmeticTypeMismatchErrsWithAMessage() {
        String expression = "code * 1";

        TransformPort.Result result = port().transform(expression, CUSTOMER_A, new ReferenceTables());

        assertTrue(TransformPort.Status.ERROR == result.status(), () -> result.toString());
        assertNotNull(result.error(), "the failure carries a human-readable error");
        assertFalse(result.error().isBlank(), "the failure carries a human-readable error");
        assertTrue(result.outputJson() == null, "no output on an error");
    }

    // A parse-level failure is also ERROR with a human-readable message, never a runtime crash.
    @Test
    void aMalformedExpressionErrsWithAMessage() {
        TransformPort.Result result =
                port().transform("{\"id\": id,", CUSTOMER_A, new ReferenceTables());

        assertTrue(TransformPort.Status.ERROR == result.status(), () -> result.toString());
        assertTrue(result.error() != null && !result.error().isBlank());
        assertTrue(result.outputJson() == null);
    }

    // ---------------------------------------------------------------- T-109: the 5 s budget

    // @negative Scenario: A runaway expression stops at 5 s - the transform stops with a
    // mapping-timeout result and nothing else is affected. Run with a short injected budget so the
    // test itself stays fast; the shape that is bounded is the same.
    @Test
    void aRunawayExpressionStopsAtTheBudgetWithMappingTimeout() {
        assertTimeoutsAt(portOfBudget(400), 400);
    }

    @Test
    void aSecondRunawayAlsoStopsAtTheBudget() {
        // regression proof of the fresh-frame budget: a runaway on one call can neither spend nor
        // poison the following call's budget (the engine's own static frame would make the time
        // bound cumulative across the process - the port must never ride it).
        TransformPort underTest = portOfBudget(400);
        assertTimeoutsAt(underTest, 400);
        assertTimeoutsAt(underTest, 400);
    }

    // The runaway workload: nested element-wise loops over a 500-entry array that run for minutes at
    // a bounded depth (the engine's evaluate-entry time check, Timebox, fires at the injected budget;
    // probe on 0.9.10 measured the stop at budget - 3 ms). $range is not part of this port's engine
    // (probed: it raises "Attempted to invoke a non-function") - the array rides the input document.
    private static final String RUNAWAY =
            "$map(a, function($x) { "
            + "  $sum($map(a, function($y) { "
            + "    $sum($map(a, function($z) { $x + $y + $z })) "
            + "  })) })";

    private static final String RUNAWAY_INPUT =
            "{\"a\": [0,1,2,3,4,5,6,7,8,9" + ",0".repeat(490) + "]}";

    private static void assertTimeoutsAt(TransformPort underTest, long budgetMs) {
        long start = System.nanoTime();

        TransformPort.Result runaway = underTest.transform(RUNAWAY, RUNAWAY_INPUT, new ReferenceTables());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(TransformPort.Status.MAPPING_TIMEOUT == runaway.status(), () -> runaway.toString());
        assertTrue(runaway.outputJson() == null, "no output on a timeout");
        assertTrue(runaway.error() != null && !runaway.error().isBlank(),
                "the timeout names its reason");
        assertTrue(elapsedMs >= budgetMs / 2, () -> "the stop never happened before it was due ("
                + elapsedMs + " ms)");
        assertTrue(elapsedMs < budgetMs + 10_000, () -> "the transform ran " + elapsedMs
                + " ms - way past the budget " + budgetMs);

        // nothing else is affected: the next transform of the same port answers normally.
        TransformPort.Result after = underTest.transform("{\"ok\": true}", "{}", new ReferenceTables());
        assertTrue(TransformPort.Status.OK == after.status(), () -> after.toString());
    }

    private static TransformPort portOfBudget(long budgetMs) {
        return new DashjoinJsonataTransformPort(budgetMs);
    }
}