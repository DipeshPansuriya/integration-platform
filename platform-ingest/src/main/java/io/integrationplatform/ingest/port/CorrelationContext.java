package io.integrationplatform.ingest.port;

import java.util.UUID;

/**
 * RED skeleton (row 278 T107, E12-F1-S1): the journey API the T-063/T-064/T-065 tests compile against. No
 * behaviour yet - the GREEN commit fills it in.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String TENANT_ID = "tenantId";
    public static final String MESSAGE_ID = "messageId";
    public static final String DELIVERY_ID = "deliveryId";

    private CorrelationContext() {
    }

    public static Scope open(String inboundTraceparent) {
        return new Scope();
    }

    public static String currentTraceparent() {
        return null;
    }

    public static String correlationIdOf(String traceparent) {
        return null;
    }

    public static void tag(String field, UUID value) {
    }

    /** One journey's scope; closing it ends the journey on this thread. */
    public static final class Scope implements AutoCloseable {

        @Override
        public void close() {
        }
    }
}
