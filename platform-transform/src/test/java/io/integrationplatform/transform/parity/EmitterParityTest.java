package io.integrationplatform.transform.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dashjoin.jsonata.Jsonata;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minidev.json.JSONValue;
import org.junit.jupiter.api.Test;

/**
 * T-036 (E7 wave 2 lane B3, row 278) - the backend parity leg: the ten reference mappings of the
 * mapping graph to JSONata are written by hand AND emitted by the drawn graph
 * ({@code ui/src/mapping/emit/emit.ts}); here BOTH strings are evaluated on the same sample
 * document through the engine itself (ADR-006: com.dashjoin:jsonata 0.9.10, called directly),
 * so a drawn mapping and a typed mapping cannot drift apart in behaviour either.
 *
 * <p>Provenance of the two columns: the emitted column is the output of the node emitter at lane
 * branch {@code worktree-sanjay-developer-integration-platform-t74}, commit 82dd290 - its
 * {@code ui/tests/mapping/emitter.test.mjs} proves hand = emitted textually, 10 of 10. If the
 * emitter changes its wording, {@link #drawnEqualsTypedTextuallyTenOfTen()} fails first -
 * regenerate both columns from the emitter and re-verify the node suite before editing here.</p>
 *
 * <p>The engine takes the parsed sample (a {@code Map}), not a JSON string - the samples are
 * parsed with json-smart, the JSON processor already on this module's test classpath through
 * jsonassert; no new dependency. Engine rules honoured (ADR-006): money rounding is never
 * {@code $round} (halves-to-even differs from the reference) - none of these expressions round
 * at all.</p>
 */
class EmitterParityTest {

    /** One reference mapping: the hand-written string and the emitter's string of the same drawing. */
    private record ReferenceMapping(String name, String typed, String emitted, String sampleJson) {
    }

    private static final String CUSTOMER_A =
            "{\"customerId\":\"C-0001\",\"orderId\":\"O-777\",\"amount\":42.5,"
            + "\"address\":{\"street\":\"Station Road\",\"city\":\"Ujjain\",\"zip\":\"456010\"},"
            + "\"lines\":[{\"sku\":\"SKU-1\"},{\"sku\":\"SKU-2\"}]}";

    /**
     * The ten reference mappings. The typed column is the hand-written expression; the emitted
     * column is what the drawn graph emits - provably the same string on the UI leg.
     */
    private static final List<ReferenceMapping> REFERENCE_MAPPINGS = List.of(
            new ReferenceMapping("flat three fields",
                    "{'customer_code': customerId, 'order_number': orderId, 'total_amount': amount}",
                    "{'customer_code': customerId, 'order_number': orderId, 'total_amount': amount}",
                    CUSTOMER_A),
            new ReferenceMapping("single drawn link",
                    "{'order_number': orderId}",
                    "{'order_number': orderId}",
                    CUSTOMER_A),
            new ReferenceMapping("nested source path flattens into navigation",
                    "{'customer_code': address.street}",
                    "{'customer_code': address.street}",
                    CUSTOMER_A),
            new ReferenceMapping("two links merge under one nested target",
                    "{'ship_to': {'street': address.street, 'zip': address.zip}}",
                    "{'ship_to': {'street': address.street, 'zip': address.zip}}",
                    CUSTOMER_A),
            new ReferenceMapping("three-level target nests two object templates",
                    "{'envelope': {'header': {'order_number': orderId}}}",
                    "{'envelope': {'header': {'order_number': orderId}}}",
                    CUSTOMER_A),
            new ReferenceMapping("array item property navigates the whole array",
                    "{'item_skus': lines.sku}",
                    "{'item_skus': lines.sku}",
                    CUSTOMER_A),
            new ReferenceMapping("nested and flat targets side by side keep drawn order",
                    "{'ship_to': {'street': address.street}, 'billing_ref': orderId}",
                    "{'ship_to': {'street': address.street}, 'billing_ref': orderId}",
                    CUSTOMER_A),
            new ReferenceMapping("drawn order is the emitted order (amount drawn before customerId)",
                    "{'total_amount': amount, 'customer_code': customerId}",
                    "{'total_amount': amount, 'customer_code': customerId}",
                    CUSTOMER_A),
            new ReferenceMapping("two levels with two children",
                    "{'header': {'party': {'code': customerId, 'name': address.city}}}",
                    "{'header': {'party': {'code': customerId, 'name': address.city}}}",
                    CUSTOMER_A),
            new ReferenceMapping("deep and flat mix",
                    "{'envelope': {'address': address.city}, 'total_amount': amount}",
                    "{'envelope': {'address': address.city}, 'total_amount': amount}",
                    CUSTOMER_A));

    /** Evaluates one expression on one sample document; the engine takes the parsed object. */
    private static Object evaluate(String expression, String sampleJson) {
        Object sample = JSONValue.parse(sampleJson);
        Object result = Jsonata.jsonata(expression).evaluate(sample);
        return result == null ? Map.of() : result; // the engine's "no match" is null, not an error
    }

    // Scenario: Drawn equals typed - typed and emitted name the same JSONata, 10 of 10.
    @Test
    void drawnEqualsTypedTextuallyTenOfTen() {
        long equal = REFERENCE_MAPPINGS.stream().filter(m -> m.emitted().equals(m.typed())).count();
        assertEquals(10, equal, () -> REFERENCE_MAPPINGS.stream()
                .filter(m -> !m.emitted().equals(m.typed())).map(ReferenceMapping::name).toList()
                + " drifted from the hand-written reference");
    }

    // Scenario: Drawn equals typed on the engine - both strings evaluate to the same output
    // on the same sample document, 10 of 10.
    @Test
    void drawnEqualsTypedOnTheEngineTenOfTen() {
        for (ReferenceMapping m : REFERENCE_MAPPINGS) {
            String typed = canonical(evaluate(m.typed(), m.sampleJson()));
            String emitted = canonical(evaluate(m.emitted(), m.sampleJson()));
            assertEquals(typed, emitted, m.name());
        }
    }

    // Scenario: A difference fails the test - a drifted expression is caught on the engine too
    // (a renamed target field produces a different document).
    @Test
    void aDifferenceIsCaughtOnTheEngine() {
        ReferenceMapping first = REFERENCE_MAPPINGS.get(0);
        String drifted = first.emitted().replace("'customer_code'", "'customer_cod'");
        String good = canonical(evaluate(first.emitted(), first.sampleJson()));
        String bad = canonical(evaluate(drifted, first.sampleJson()));
        assertNotEquals(good, bad, "a drifted expression must not pass the parity leg");
    }

    /**
     * Engine-result equality: maps compare key-sorted (the engine materialises its own list/map
     * types), lists element-wise in order (order is honest), scalars by their string.
     */
    private static String canonical(Object result) {
        if (result instanceof Map<?, ?> map) {
            StringBuilder inner = new StringBuilder();
            new TreeMap<>(map).forEach((key, value) -> {
                if (!inner.isEmpty()) {
                    inner.append(", ");
                }
                inner.append(key).append("=").append(canonical(value));
            });
            return "{" + inner + "}";
        }
        if (result instanceof List<?> list) {
            StringBuilder inner = new StringBuilder();
            list.forEach(item -> {
                if (!inner.isEmpty()) {
                    inner.append(", ");
                }
                inner.append(canonical(item));
            });
            return "[" + inner + "]";
        }
        return String.valueOf(result);
    }
}