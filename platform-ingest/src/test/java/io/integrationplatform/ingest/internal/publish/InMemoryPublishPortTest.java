package io.integrationplatform.ingest.internal.publish;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T-042 (E2-F1-S2): the in-memory {@code PublishPort} adapter every E2 test uses before Kafka (E8 replaces it in
 * platform-routing). It records published records in order, acknowledges by default, and can be told to refuse the
 * acknowledge of one row so the relay's unmarked-row path is testable.
 */
class InMemoryPublishPortTest {

    private static OutboundRecord record(long outboxId) {
        return new OutboundRecord("platform.message.accepted", "key-" + outboxId, outboxId,
                UUID.nameUUIDFromBytes(("agg-" + outboxId).getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
    }

    @Test
    void publishReturnsAReceiptForItsRowAndRecordsTheRecordInOrder() {
        InMemoryPublishPort port = new InMemoryPublishPort();

        PublishReceipt first = port.publish(record(1));
        PublishReceipt second = port.publish(record(2));

        assertThat(first.outboxId()).isEqualTo(1);
        assertThat(second.outboxId()).isEqualTo(2);
        assertThat(port.published()).extracting(OutboundRecord::outboxId).containsExactly(1L, 2L);
    }

    @Test
    void acknowledgeSucceedsForEverythingPublished() {
        InMemoryPublishPort port = new InMemoryPublishPort();

        PublishReceipt receipt = port.publish(record(7));

        assertThat(port.acknowledge(receipt, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void acknowledgeCanRefuseOneRowWithoutTouchingTheOthers() {
        InMemoryPublishPort port = new InMemoryPublishPort();
        PublishReceipt refused = port.publish(record(1));
        PublishReceipt accepted = port.publish(record(2));

        port.refuseAcknowledgeOf(1);

        assertThat(port.acknowledge(refused, Duration.ofSeconds(5))).isFalse();
        assertThat(port.acknowledge(accepted, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void publishedRecordsKeepTheirRowIdempotencyKey() {
        InMemoryPublishPort port = new InMemoryPublishPort();

        port.publish(record(4));
        port.publish(record(4)); // a re-publish after a crash carries the same key (T-010 detects it)

        List<OutboundRecord> records = port.published();
        assertThat(records).extracting(OutboundRecord::idempotencyKey)
                .containsExactly("outbox:4", "outbox:4");
    }

    @Test
    void concurrentPublishesFromTwoRelaysAllLandInTheLog() throws Exception {
        InMemoryPublishPort port = new InMemoryPublishPort();
        Thread left = new Thread(() -> port.publish(record(11)));
        Thread right = new Thread(() -> port.publish(record(12)));

        left.start();
        right.start();
        left.join(Duration.ofSeconds(5));
        right.join(Duration.ofSeconds(5));

        assertThat(port.published()).extracting(OutboundRecord::outboxId).containsExactlyInAnyOrder(11L, 12L);
    }
}
