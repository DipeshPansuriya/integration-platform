package io.integrationplatform.transform.internal;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.json.Json;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.util.Objects;

/**
 * T-108 (E7 wave-1 lane B2): the {@link TransformPort} on the real engine - Dashjoin JSONata 0.9.10
 * ({@code com.dashjoin.jsonata}) called directly; the module never imports {@code org.apache.camel}
 * (E7-C8, ADR-006 Accepted). Lookups resolve ONLY through the {@link LookupSource} seam, exposed to the
 * expression as one {@code $lookup(table, key)} function - the same shape lane A's spike registered
 * (results.md), so a lookup edit stays a data edit (T-163: immediate).
 *
 * <p>T-109 (E7-C10): the hard per-transform budget is a constructor argument - the runtime wiring reads it
 * from {@code config/transform.yml} (R07: the environment-variable name) and every call runs inside that
 * budget. The engine bounds are set on a FRESH frame per call ({@code Frame.setRuntimeBounds})
 * - never the engine's shared static frame - so a runaway on one mapping cannot spend or poison another's
 * budget. A runaway stops on the time bound (Timebox: "Expression evaluation timeout") or on the recursion
 * bound ("Stack overflow error" - a non-terminating recursive expression is caught before its own JVM stack
 * is the resource it burns); both are runaway stops with {@link Status#MAPPING_TIMEOUT}. Any other engine
 * failure is {@link Status#ERROR} with the engine's own human-readable message, and a failed expression
 * never produces output.</p>
 *
 * <p>ADR-006 rule 1 (string boundary) and rule 8 (the engine's arithmetic is passed through unrounded - a
 * mapping is never given a silently rounding {@code $round}): nothing here touches values, only the
 * container.</p>
 */
public final class DashjoinJsonataTransformPort implements TransformPort {

    /** The recursion bound handed to {@code Frame.setRuntimeBounds}: the engine's own default depth. */
    private static final int MAX_RECURSION_DEPTH = 100;

    private final long budgetMs;

    /** The hard per-transform budget in milliseconds (T-109); the wiring's single configured figure. */
    public DashjoinJsonataTransformPort(long budgetMs) {
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("the transform budget must be a positive number of milliseconds");
        }
        this.budgetMs = budgetMs;
    }

    @Override
    public Result transform(String expression, String inputJson, LookupSource lookups) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(inputJson, "inputJson");
        LookupSource lookupsOfTheCall = Objects.requireNonNull(lookups, "lookups");
        try {
            Jsonata engine = Jsonata.jsonata(expression);
            engine.registerFunction("lookup",
                    (Jsonata.Fn2<Object, Object, Object>) (table, key) ->
                            lookupsOfTheCall.entry(asText(table), asText(key)));

            Jsonata.Frame frame = engine.createFrame();
            frame.setRuntimeBounds(budgetMs, MAX_RECURSION_DEPTH);
            Object evaluated = engine.evaluate(parseInput(inputJson), frame);

            return new Result(Status.OK, JsonWriter.write(evaluated), null);
        } catch (JException engineFailure) {
            String error = engineFailure.getError();
            if (isRunawayStop(error)) {
                return new Result(Status.MAPPING_TIMEOUT, null,
                        "the transform stopped at the " + budgetMs + " ms mapping budget (a runaway expression)");
            }
            return new Result(Status.ERROR, null, error);
        } catch (com.dashjoin.jsonata.json.ParseException badInput) {
            return new Result(Status.ERROR, null, "the mapping sample input is not valid JSON: "
                    + plainMessage(badInput));
        } catch (Exception failure) {
            return new Result(Status.ERROR, null, plainMessage(failure));
        }
    }

    private static Object parseInput(String inputJson) {
        return Json.parseJson(inputJson);
    }

    /** The engine's two stop messages that mean RUNAWAY (time exhausted or recursion bound), not a data error. */
    private static boolean isRunawayStop(String error) {
        // NOTE: T1006 in this port ("Attempted to invoke a non-function", probed on 0.9.10) is an
        // application error, never a runaway stop - only the Timebox messages mean the bounds fired.
        return error != null && (error.toLowerCase().contains("timeout") || error.contains("Stack overflow"));
    }

    private static String plainMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The only writer of the result string (ADR-006 rule 1): valid JSON of exactly the evaluated value -
     * objects, arrays, strings (escaped), numbers, booleans, null. Written here because the engine returns
     * JDK structures and carries no JSON writer of its own at compile scope, and no Jackson enters this
     * module (ADR-006: zero engine Jackson dependency, the string boundary verified by T-032).
     */
    private static final class JsonWriter {

        private static String write(Object value) {
            StringBuilder json = new StringBuilder();
            writeValue(json, value);
            return json.toString();
        }

        private static void writeValue(StringBuilder json, Object value) {
            if (value == null) {
                json.append("null");
                return;
            }
            if (value instanceof String string) {
                writeString(json, string);
                return;
            }
            if (value instanceof Boolean || value instanceof Number) {
                json.append(value);
                return;
            }
            if (value instanceof java.util.Map<?, ?> map) {
                json.append('{');
                boolean first = true;
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    writeString(json, String.valueOf(entry.getKey()));
                    json.append(':');
                    writeValue(json, entry.getValue());
                }
                json.append('}');
                return;
            }
            if (value instanceof Iterable<?> items) {
                json.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    writeValue(json, item);
                }
                json.append(']');
                return;
            }
            writeString(json, String.valueOf(value));
        }

        private static void writeString(StringBuilder json, String string) {
            json.append('"');
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                switch (c) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            json.append(String.format("\\u%04x", (int) c));
                        } else {
                            json.append(c);
                        }
                    }
                }
            }
            json.append('"');
        }
    }
}