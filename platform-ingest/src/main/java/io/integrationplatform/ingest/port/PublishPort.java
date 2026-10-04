package io.integrationplatform.ingest.port;

import java.time.Duration;

/**
 * The one narrow port the outbox relay publishes through (ADR-005; E2-F1-S2), so E8 plugs Kafka in without touching
 * the relay. The relay calls {@link #publish} and {@link #acknowledge} and nothing else, and no vendor client class is
 * imported by the relay (architecture test, T-041).
 *
 * <p>Implemented by lane B's in-memory adapter (T-042) and, from E8, the Kafka adapter in platform-routing.
 */
public interface PublishPort {

    /** Sends one record and returns without waiting for the broker. */
    PublishReceipt publish(OutboundRecord record);

    /**
     * Waits until the broker acknowledged the record.
     *
     * @return {@code true} when acknowledged; {@code false} when it failed or {@code timeout} passed - the relay then
     *     leaves the row unpublished for a later cycle and marks nothing
     */
    boolean acknowledge(PublishReceipt receipt, Duration timeout);
}
