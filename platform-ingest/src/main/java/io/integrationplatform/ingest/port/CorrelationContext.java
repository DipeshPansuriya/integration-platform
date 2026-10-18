package io.integrationplatform.ingest.port;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * One message journey's correlation id (row 278 T107, E12-F1-S1; ADR-005 Decision 9). The id is the W3C trace id of
 * the journey's {@code traceparent}: taken from the inbound value when it is a valid W3C version-00 traceparent,
 * started fresh otherwise - so a crafted header never becomes a log field. The traceparent is what the outbox stores
 * and the relay restores as the Kafka record's one header, so the next stage opens the same journey from it.
 *
 * <p>A journey lives on the opening thread only, between {@link #open} and {@link Scope#close}: the correlation id
 * and the {@link #tag tags} (tenant, message, delivery) are SLF4J MDC fields - every log line written inside carries
 * them - and closing the scope restores exactly what the enclosing scope had, nothing leaks onto a pooled thread.
 *
 * <p>ponytail: no tracing library is on the classpath (no micrometer-tracing bridge); the Kafka header path already
 * carries the traceparent, so the journey is this thread-local plus MDC - spans are a later slice.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String TENANT_ID = "tenantId";
    public static final String MESSAGE_ID = "messageId";
    public static final String DELIVERY_ID = "deliveryId";

    private static final Set<String> TAGS = Set.of(TENANT_ID, MESSAGE_ID, DELIVERY_ID);
    private static final Set<String> FIELDS = Set.of(CORRELATION_ID, TENANT_ID, MESSAGE_ID, DELIVERY_ID);

    /** W3C Trace Context version 00: lowercase hex only, exactly 55 characters. */
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_PARENT = "0".repeat(16);

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
    }

    /** Opens a journey from the inbound traceparent (a fresh one when absent or not a valid W3C value). */
    public static Scope open(String inboundTraceparent) {
        String traceparent = correlationIdOf(inboundTraceparent) != null ? inboundTraceparent : fresh();
        Scope scope = new Scope(CURRENT.get(), MDC.getCopyOfContextMap());
        CURRENT.set(traceparent);
        TAGS.forEach(MDC::remove);
        MDC.put(CORRELATION_ID, correlationIdOf(traceparent));
        return scope;
    }

    /** The open journey's traceparent on this thread, or {@code null} outside a journey. */
    public static String currentTraceparent() {
        return CURRENT.get();
    }

    /** The correlation id (the W3C trace id) of a valid traceparent, or {@code null} when it is not one. */
    public static String correlationIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        var match = TRACEPARENT.matcher(traceparent);
        if (!match.matches() || ZERO_TRACE.equals(match.group(1)) || ZERO_PARENT.equals(match.group(2))) {
            return null;
        }
        return match.group(1);
    }

    /** Adds a journey field to every log line of the open journey; outside a journey it does nothing. */
    public static void tag(String field, UUID value) {
        if (!TAGS.contains(field)) {
            throw new IllegalArgumentException("not a journey tag: " + field);
        }
        if (CURRENT.get() != null && value != null) {
            MDC.put(field, value.toString());
        }
    }

    private static String fresh() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String traceId = UUID.randomUUID().toString().replace("-", ""); // random, never all zero
        return "00-" + traceId + "-" + String.format("%016x", random.nextLong(1, Long.MAX_VALUE)) + "-01";
    }

    /** One journey's scope; closing it ends the journey on this thread and restores the enclosing one. */
    public static final class Scope implements AutoCloseable {

        private final String previous;
        private final Map<String, String> previousFields;

        private Scope(String previous, Map<String, String> previousFields) {
            this.previous = previous;
            this.previousFields = previousFields == null ? Map.of() : previousFields;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            for (String field : FIELDS) {
                String value = previousFields.get(field);
                if (value == null) {
                    MDC.remove(field);
                } else {
                    MDC.put(field, value);
                }
            }
        }
    }
}
