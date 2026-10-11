package io.integrationplatform.ingest.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a caller asks the outbox to publish. The tenant is never part of it: the writer takes it from the bound
 * {@code TenantContext} (R08, ADR-007 Decision 2).
 *
 * @param topic       the work topic
 * @param aggregateId the id the record carries - the message id or the delivery id (records carry ids only, ADR-011 D7)
 * @param recordKey   the record key (ADR-011 Decision 7); the writer derives the key bucket 0-63 from it (ADR-005 D11)
 * @param traceparent the W3C trace context to restore on publish, or {@code null} when none
 * @param availableAt the earliest publish time, or {@code null} for "now" (deferred rows, ADR-011 Decision 5)
 * @param contractVersionId the contract version the caller resolved for this record (T-106, ADR-011 Decision 3
 *                    rule 3), or {@code null} when the record carries none
 */
public record OutboxEntry(OutboxTopic topic, UUID aggregateId, String recordKey, String traceparent,
        Instant availableAt, UUID contractVersionId) {

    public OutboxEntry {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(recordKey, "recordKey");
    }

    /** An entry without a contract version - the E2 shape, unchanged for every existing caller. */
    public OutboxEntry(OutboxTopic topic, UUID aggregateId, String recordKey, String traceparent,
            Instant availableAt) {
        this(topic, aggregateId, recordKey, traceparent, availableAt, null);
    }
}
