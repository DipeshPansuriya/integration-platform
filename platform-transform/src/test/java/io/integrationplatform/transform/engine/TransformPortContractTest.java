package io.integrationplatform.transform.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dashjoin.jsonata.json.Json;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * T-108 + T-109 (E7 wave 1 lane B2, row 278) - the TransformPort contract, FAILING FIRST: the real
 * port over com.dashjoin:jsonata 0.9.10, with every case lane A proved in the spike
 * (platform-transform/src/test/.../spike at worktree-amit...t50, commit e39d24f) run here against the
 * port, not the raw engine.
 *
 * <pre>
 * G1 (T-108, the five breaking cases, each one unit test):
 *   Lookups ride the registered $lookup(table, key) function over the LookupSource seam
 *   Conditionals, array restructuring and enrichment produce exactly the spike's expected outputs
 *   An arithmetic type mismatch is a describable failure (ERROR), never a silent wrong answer
 * G2 (T-109): a runaway expression returns MAPPING_TIMEOUT, stops within its budget plus a small
 *   margin, and the worker is free again - a second transform on the same engine succeeds right after
 * </pre>
 *
 * <p>String boundary (ADR-006 rule 6): the port takes the JSON string, parses it with the engine's own
 * parser and answers one canonical JSON string (keys sorted, integral numbers normalised) - the conversion
 * the spike's JsonStrings previewed for this port.</p>
 */
class TransformPortContractTest {

    /** Lane A's spike reference payload (the "orders" interface), shared by all five cases. */
    private static final String ORDERS_PAYLOAD =
            "{\"id\":\"O-1001\",\"customer\":{\"ref\":\"C-42\",\"name\":\"Amit\"},\"category\":\"b2b\","
            + "\"code\":\"S2\",\"items\":[{\"sku\":\"A1\",\"qty\":2,\"price\":10.0},{\"sku\":\"B2\",\"qty\":1,\"price\":5.5}]}";

    /** Lane A's spike tables, served through the seam only - never baked into an expression. */
    private static final LookupSource SPIKE_LOOKUPS = (tableName, key) -> switch (tableName) {
        case "order_status" -> "S2".equals(key) ? "Pending" : null;
        case "customer_tier" -> "C-42".equals(key) ? "gold" : null;
        case "customer_profile_name" -> "C-42".equals(key) ? "Amit Bhandari" : null;
        default -> null;
    };

    /** The subject: the port every caller uses; the 5 s production default is set at wiring. */
    private TransformPort port() {
        return new JsonataTransformEngine(5_000, 200);
    }

    // G1 case 1 - Lookups: the mapped status value comes from the LookupSource seam.
    @Test
    void lookupsResolveThroughTheSeam() {
        TransformPort.Result result = port().transform(
                "{\"id\": id, \"status\": $lookup(\"order_status\", code)}", ORDERS_PAYLOAD, SPIKE_LOOKUPS);
        assertEquals(TransformPort.Status.OK, result.status(), result.toString());
        assertEquals(canonical("{\"id\":\"O-1001\",\"status\":\"Pending\"}"), result.outputJson());
    }

    // G1 case 2 - Conditionals: one branch per category value, decided at runtime.
    @Test
    void conditionalsTakeTheRuntimeBranch() {
        TransformPort.Result result = port().transform(
                "{\"id\": id, \"channel\": category = \"b2b\" ? \"partner\" : \"direct\"}",
                ORDERS_PAYLOAD, SPIKE_LOOKUPS);
        assertEquals(TransformPort.Status.OK, result.status(), result.toString());
        assertEquals(canonical("{\"id\":\"O-1001\",\"channel\":\"partner\"}"), result.outputJson());
    }

    // G1 case 3 - Array restructuring: one output line per item, per-element arithmetic, aggregate gross.
    @Test
    void arraysRestructureElementWiseWithAnAggregate() {
        TransformPort.Result result = port().transform(
                "{\"orderId\": id, \"lines\": items.{\"sku\": sku, \"total\": qty * price}, "
                + "\"gross\": $sum($map(items, function($i) { $i.qty * $i.price }))}",
                ORDERS_PAYLOAD, SPIKE_LOOKUPS);
        assertEquals(TransformPort.Status.OK, result.status(), result.toString());
        assertEquals(canonical("{\"orderId\":\"O-1001\",\"lines\":[{\"sku\":\"A1\",\"total\":20},"
                + "{\"sku\":\"B2\",\"total\":5.5}],\"gross\":25.5}"), result.outputJson());
    }

    // G1 case 4 - Enrichment: two lookup rows keyed by customer.ref joined into the output.
    @Test
    void enrichmentJoinsLookupRowsIntoTheOutput() {
        TransformPort.Result result = port().transform(
                "{\"id\": id, \"tier\": $lookup(\"customer_tier\", customer.ref), "
                + "\"displayName\": $lookup(\"customer_profile_name\", customer.ref)}",
                ORDERS_PAYLOAD, SPIKE_LOOKUPS);
        assertEquals(TransformPort.Status.OK, result.status(), result.toString());
        assertEquals(canonical("{\"id\":\"O-1001\",\"tier\":\"gold\",\"displayName\":\"Amit Bhandari\"}"),
                result.outputJson());
    }

    // G1 case 5 - Error translation: a describable failure, never a silent wrong answer.
    @Test
    void anArithmeticTypeMismatchIsADescribableError() {
        TransformPort.Result result = port().transform("code * 1", ORDERS_PAYLOAD, SPIKE_LOOKUPS);
        assertEquals(TransformPort.Status.ERROR, result.status());
        assertNull(result.outputJson());
        assertNotNull(result.error());
        assertTrue(!result.error().isBlank(), "the error must be human-readable");
        assertTrue(result.error().matches("(?sim).*(string|number|multiply|S2).*"),
                "the error names the mismatch: " + result.error());
    }

    /** The port answers with the engine's parse refusal, still a describable ERROR, never a crash. */
    @Test
    void anUnparseablePayloadIsAnErrorNotACrash() {
        TransformPort.Result result = port().transform("$.id", "{\"id\":", SPIKE_LOOKUPS);
        assertEquals(TransformPort.Status.ERROR, result.status());
        assertNull(result.outputJson());
        assertNotNull(result.error());
    }

    // G2 - T-109: a runaway expression is stopped by the budget and MAPPING_TIMEOUT is the answer.
    // The budget here is 500 ms; the production port is wired at 5 s from config/transform.yml body.
    @Test
    void aRunawayExpressionStopsAtTheBudgetAndTheWorkerIsFreeAfterwards() {
        JsonataTransformEngine runawayBudget = new JsonataTransformEngine(500, 200);
        long t0 = System.currentTimeMillis();
        TransformPort.Result result = runawayBudget.transform(
                "( $loop := function($n) { $loop($n + 1) } ; $loop(0) )", "{}", SPIKE_LOOKUPS);
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(TransformPort.Status.MAPPING_TIMEOUT, result.status());
        assertNull(result.outputJson());
        assertTrue(elapsed < 5_000, "stopped well before the outer bound, took " + elapsed + " ms");

        // the worker is free: the next transform on the same engine runs at once and succeeds
        long t1 = System.currentTimeMillis();
        TransformPort.Result after = runawayBudget.transform("{}",
                "{\"id\":\"O-1001\",\"customer\":{\"ref\":\"C-42\"}}",
                (tableName, key) -> "customer_tier".equals(tableName) && "C-42".equals(key) ? "gold" : null);
        assertTrue(System.currentTimeMillis() - t1 < 5_000, "the freed worker serves the next call");
        assertEquals(TransformPort.Status.OK, after.status(), after.toString());
    }

    /** Canonical form of an expected JSON document for comparison against the port's canonical answer
     * (keys sorted, integral numbers normalised): port side and test side must agree on the one shape. */
    private static String canonical(String expectedJson) {
        return render(Json.parseJson(expectedJson));
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d)
                    && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (value instanceof Boolean b) {
            return String.valueOf(b);
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        if (value instanceof Iterable<?> list) {
            StringBuilder inner = new StringBuilder();
            for (Object item : list) {
                if (!inner.isEmpty()) inner.append(",");
                inner.append(render(item));
            }
            return "[" + inner + "]";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder inner = new StringBuilder();
            for (Map.Entry<?, ?> e : new TreeMap<Object, Object>(map).entrySet()) {
                if (!inner.isEmpty()) inner.append(",");
                inner.append(render(String.valueOf(e.getKey()))).append(":").append(render(e.getValue()));
            }
            return "{" + inner + "}";
        }
        return render(String.valueOf(value));
    }
}