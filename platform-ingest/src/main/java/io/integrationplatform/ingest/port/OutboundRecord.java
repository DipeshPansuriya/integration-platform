package io.integrationplatform.ingest.port;

import java.util.Objects;
import java.util.UUID;

/**
 * One outbox row as the relay hands it to the {@link PublishPort}. It carries ids only (ADR-011 Decision 7) and no
 * tenant: the receiving side resolves the tenant from the row the id names, never from the record (T1).
 *
 * @param topic       the topic name ({@link OutboxTopic#topicName()})
 * @param key         the record key
 * @param outboxId    the outbox row this record was read from
 * @param aggregateId the message id or delivery id
 * @param traceparent the stored W3C trace context, or {@code null}
 */
public record OutboundRecord(String topic, String key, long outboxId, UUID aggregateId, String traceparent) {

    public OutboundRecord {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(aggregateId, "aggregateId");
    }

    /**
     * The idempotency key of this record: the same for every re-publish of one outbox row (a crash after publish and
     * before the mark publishes the row again), different for every other row. The receiving side detects a
     * duplicate by it (T-010).
     */
    public String idempotencyKey() {
        return "outbox:" + outboxId;
    }
}
