package io.integrationplatform.ingest.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Seam lock (E2, arjun-fullstack): the record contracts three lanes build on - the idempotency key is the relay's
 * re-publish identity (T-010 consumes it; ADR-011 Decision 7), and the null rules fix what a writer must supply.
 * Lanes: do not edit this test - a needed change goes through arjun-fullstack.
 */
class OutboundRecordTest {

    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-4000-8000-000000000001");

    private static OutboundRecord record(long outboxId) {
        return new OutboundRecord(OutboxTopic.MESSAGE_ACCEPTED.topicName(), "tenant-a/order-1", outboxId, MESSAGE_ID,
                null);
    }

    @Test
    void idempotencyKeyIsStablePerOutboxRowAcrossRepublish() {
        assertThat(record(42L).idempotencyKey())
                .isEqualTo("outbox:42")
                .isEqualTo(record(42L).idempotencyKey());
    }

    @Test
    void idempotencyKeyDiffersForDifferentOutboxRows() {
        assertThat(record(42L).idempotencyKey()).isNotEqualTo(record(43L).idempotencyKey());
    }

    @Test
    void topicKeyAndAggregateIdAreRequiredTraceparentIsNullable() {
        assertThatThrownBy(() -> new OutboundRecord(null, "k", 1L, MESSAGE_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboundRecord("t", null, 1L, MESSAGE_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboundRecord("t", "k", 1L, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(new OutboundRecord("t", "k", 1L, MESSAGE_ID, "00-trace-span-01").traceparent())
                .isEqualTo("00-trace-span-01");
    }

    @Test
    void outboxEntryRequiresTopicAggregateIdAndKeyButNotTraceOrSchedule() {
        assertThatThrownBy(() -> new OutboxEntry(null, MESSAGE_ID, "k", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxEntry(OutboxTopic.DELIVERY_DUE, null, "k", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxEntry(OutboxTopic.DELIVERY_DUE, MESSAGE_ID, null, null, null))
                .isInstanceOf(NullPointerException.class);
        OutboxEntry deferred =
                new OutboxEntry(OutboxTopic.DELIVERY_DUE, MESSAGE_ID, "k", null, Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(deferred.availableAt()).isNotNull();
        assertThat(deferred.traceparent()).isNull();
    }
}
