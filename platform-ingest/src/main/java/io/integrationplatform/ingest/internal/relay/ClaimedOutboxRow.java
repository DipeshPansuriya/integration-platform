package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.ingest.port.OutboxTopic;
import java.util.Objects;
import java.util.UUID;

/**
 * One outbox row as the claim returned it: everything the relay needs to build the {@code OutboundRecord} and to
 * mark the row back, and nothing else (records carry ids only, ADR-011 Decision 7).
 *
 * @param outboxId    the outbox row id
 * @param topic       the topic the row stores (already resolved from the {@code CHECK}-closed text)
 * @param aggregateId the message or delivery id the record carries
 * @param recordKey   the stored record key
 * @param traceparent the stored W3C trace context, or {@code null}
 */
public record ClaimedOutboxRow(long outboxId, OutboxTopic topic, UUID aggregateId, String recordKey,
        String traceparent) {

    public ClaimedOutboxRow {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(recordKey, "recordKey");
    }
}
