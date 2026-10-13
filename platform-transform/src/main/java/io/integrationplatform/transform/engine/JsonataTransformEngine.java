package io.integrationplatform.transform.engine;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.json.Json;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * E7 wave 1 lane B2, T-108 + T-109 (ADR-006 decisions 1, 4, 5; row 278): the one TransformPort. The
 * engine library's types never leave this module - callers see only the port on the string boundary
 * (ADR-006 rule 1): the payload JSON string is parsed with the engine's own parser (no Jackson of any
 * major reaches this module, ADR-006 rule 6) and the output is flattened to one canonical JSON string
 * (keys sorted, integral numbers normalised) - the same conversion the spike previewed in JsonStrings.
 *
 * <p>Lookups ride the one registered {@code $lookup(table, key)} function over the LookupSource seam
 * (ADR-006 decision 4 - a snapshot read, immediate, no versioning; the function is registered per call
 * because the seam arrives per call). Nothing else is registered, the built-in function set stays
 * closed, and this module registers no I/O-capable function.</p>
 *
 * <p>The budget (T-109, ADR-006 rule 5): evaluation runs on a small bounded pool of platform threads -
 * never virtual threads, a CPU-bound runaway would pin a carrier - and the engine's native runtime bound
 * ({@code Frame.setRuntimeBounds(timeout, maxDepth)}) stops the runaway itself; the outer wait is the
 * belt behind it. Either way the caller gets {@link TransformPort.Status#MAPPING_TIMEOUT} and the worker
 * returns free - the next transform on the same pool runs at once (proved in
 * TransformPortContractTest). The budget and depth come from the wiring: their production defaults live
 * in this module's {@code config/transform.yml} body ({@code transform.budget-millis: 5000},
 * {@code transform.max-depth: 200}), imported by application.yml; the constructor takes the resolved
 * values so this class stays engine-only (no Spring type).</p>
 *
 * <p>Dialect rule 8 of ADR-006 honoured by omission: no money rounding is registered, so {@code $round}
 * (halves-to-even in this port) can never sit on a monetary path unnoticed.</p>
 */
public class JsonataTransformEngine implements TransformPort {

    /** The small bounded pool of platform threads all engine instances share (ADR-006 rule 5). */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread worker = new Thread(runnable, "transform-budget");
        worker.setDaemon(true);
        return worker;
    });

    /** Slack over the engine's own timeout before the outer wait also gives up: the wait must never
     * win against the native bound when that is healthy, and must cap a hung native bound. */
    private static final long OUTER_SLACK_MILLIS = 1_000;

    private final long budgetMillis;
    private final int maxDepth;

    public JsonataTransformEngine(long budgetMillis, int maxDepth) {
        if (budgetMillis <= 0 || maxDepth <= 0) {
            throw new IllegalArgumentException(
                    "a positive transform budget and depth are required (T-109: " + budgetMillis + " ms, "
                            + maxDepth + " depth)");
        }
        this.budgetMillis = budgetMillis;
        this.maxDepth = maxDepth;
    }

    @Override
    public Result transform(String expression, String inputJson, LookupSource lookups) {
        Object payload;
        try {
            payload = Json.parseJson(inputJson);
        } catch (RuntimeException e) {
            return new Result(Status.ERROR, null, "the payload is not valid JSON: " + first(e));
        }
        Jsonata engine;
        try {
            engine = Jsonata.jsonata(expression);
            engine.registerFunction("lookup", (Jsonata.Fn2<Object, Object, Object>) (table, key) ->
                    lookups == null ? null : lookups.entry(String.valueOf(table), String.valueOf(key)));
        } catch (RuntimeException e) {
            return new Result(Status.ERROR, null, "the expression does not compile: " + first(e));
        }
        Jsonata.Frame frame = engine.createFrame();
        frame.setRuntimeBounds(budgetMillis, maxDepth);

        Future<Object> run = POOL.submit(() -> engine.evaluate(payload, frame));
        try {
            return new Result(Status.OK, writeCanonical(run.get(budgetMillis + OUTER_SLACK_MILLIS,
                    TimeUnit.MILLISECONDS)), null);
        } catch (TimeoutException e) {
            run.cancel(true);
            return new Result(Status.MAPPING_TIMEOUT, null,
                    "the transform exceeded its " + budgetMillis + " ms budget (T-109, E7-C10)");
        } catch (Exception e) {
            if (stoppedByTheBudget(e)) {
                return new Result(Status.MAPPING_TIMEOUT, null,
                        "the transform exceeded its " + budgetMillis + " ms budget (T-109, E7-C10)");
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Result(Status.ERROR, null, firstOf(cause));
        }
    }

    /** True when the engine stopped for the budget (its native time box: "evaluation timeout ... infinite loop"). */
    private static boolean stoppedByTheBudget(Throwable error) {
        String message = String.valueOf(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
        return message.toLowerCase().contains("timeout") || message.toLowerCase().contains("infinite loop");
    }

    private static String first(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.toString() : error.getMessage();
    }

    /** The engine's error in one human-readable line: its code (T2001...) with its message, never bare. */
    private static String firstOf(Throwable cause) {
        if (cause instanceof JException je && je.getError() != null && !je.getError().isBlank()) {
            String message = je.getMessage();
            return message == null || message.isBlank() || message.equals(je.getError())
                    ? je.getError() : je.getError() + ": " + message;
        }
        return first(cause);
    }

    /**
     * The port's canonical answer string: keys sorted (the engine's object model backs objects with
     * unordered maps), integral numbers normalised ({@code 20.0} becomes {@code 20}), null as
     * {@code null}. The spike's JsonStrings canonical form, owned by the port (ADR-006 rule 6).
     */
    private static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        render(value, sb);
        return sb.toString();
    }

    private static void render(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d)
                    && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof String s) {
            renderString(s, sb);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : new TreeMap<Object, Object>(map).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                renderString(String.valueOf(entry.getKey()), sb);
                sb.append(':');
                render(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                render(item, sb);
            }
            sb.append(']');
        } else {
            renderString(String.valueOf(value), sb);
        }
    }

    private static void renderString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String escaped = switch (c) {
                case '\\' -> "\\\\";
                case '"' -> "\\\"";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> null;
            };
            if (escaped != null) {
                sb.append(escaped);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}