package io.integrationplatform.routing.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * T-110 (E8-F1-S1, E8-C2, E8-C5) JVM behaviour of the Kafka {@code PublishPort} adapter, against a fake
 * {@link Producer}: the record's Kafka key is the stored record key verbatim (SHA-256 of tenant, interface,
 * business key - ADR-011 Decision 7), the value carries ids only in the {@code {"v","type","id"}} shape, the
 * row's stored {@code traceparent} is the record's ONLY header (and none when the row has none), {@code
 * publish} is fire-and-forget, and {@code acknowledge} answers the broker's outcome inside the timeout. The
 * same-key ordering against a real broker and database is the container sibling.
 */
class KafkaPublishPortTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String RECORD_KEY = "1a2b3c4d5e6f7081a2b3c4d5e6f7081a2b3c4d5e6f7081a2b3c4d5e6f7081a2b3";
    private static final String AGGREGATE = "b16a5c9d-5e5f-4a1b-9c2d-3e4f5a6b7c8d";

    @SuppressWarnings("unchecked")
    private final Producer<String, String> producer = mock(Producer.class);

    private final KafkaPublishPort port = new KafkaPublishPort(producer);

    @Test
    void aPublishedRecordCarriesTheStoredRecordKeyAndTheIdsOnlyValue() {
        ProducerRecord<String, String> sent = sent("platform.message.accepted", RECORD_KEY, TRACEPARENT, 7);

        assertThat(sent.key()).as("the Kafka key is the stored record key (ADR-011 D7, E8-C2)")
                .isEqualTo(RECORD_KEY);
        assertThat(sent.value())
                .isEqualTo("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + AGGREGATE + "\"}");
        assertThat(sent.topic()).isEqualTo("platform.message.accepted");
    }

    @Test
    void aDeliveryDueRecordMapsItsTypeIntoTheIdsOnlyValue() {
        assertThat(sent("platform.delivery.due", RECORD_KEY, null, 8).value())
                .isEqualTo("{\"v\":1,\"type\":\"delivery.due\",\"id\":\"" + AGGREGATE + "\"}");
    }

    @Test
    void theStoredTraceparentIsTheRecordsOnlyHeader() {
        ProducerRecord<String, String> sent = sent("platform.message.accepted", RECORD_KEY, TRACEPARENT, 9);

        assertThat(sent.headers()).as("the trace rides as the one header (T-112, E8-C5): nothing beside it")
                .hasSize(1);
        assertThat(new String(sent.headers().lastHeader("traceparent").value())).isEqualTo(TRACEPARENT);
    }

    @Test
    void aRowWithoutAStoredTraceCarriesNoHeaderAtAll() {
        assertThat(sent("platform.message.accepted", RECORD_KEY, null, 10).headers()).isEmpty();
    }

    @Test
    void theReceiptNamesTheOutboxRowItBelongsTo() {
        PublishReceipt receipt = port.publish(row(11, null));

        assertThat(receipt.outboxId()).isEqualTo(11);
    }

    @Test
    void acknowledgeIsTrueWhenTheBrokerConfirmsInsideTheTimeout() {
        stubSend(CompletableFuture.completedFuture(null));

        assertThat(port.acknowledge(port.publish(row(12, TRACEPARENT)), Duration.ofSeconds(5)))
                .as("the broker accepted the record").isTrue();
    }

    @Test
    void acknowledgeIsFalseWhenTheBrokerDoesNotConfirmBeforeTheTimeout() {
        stubSend(new CompletableFuture<>()); // the broker never answers inside the test's window

        assertThat(port.acknowledge(port.publish(row(13, null)), Duration.ofMillis(30)))
                .as("no broker answer inside the timeout: the row is left unpublished for a later cycle")
                .isFalse();
    }

    @Test
    void acknowledgeIsFalseWhenTheBrokerRejectsTheRecord() {
        CompletableFuture<RecordMetadata> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new TimeoutException("broker did not answer"));
        stubSend(rejected);

        assertThat(port.acknowledge(port.publish(row(14, null)), Duration.ofSeconds(5)))
                .as("a failed send is an answer of no, not an exception that loses the cycle")
                .isFalse();
    }

    // ---------- helpers ----------

    /** Publishes one row through the port and returns what the producer actually received. */
    private ProducerRecord<String, String> sent(String topic, String key, String traceparent, long outboxId) {
        stubSend(CompletableFuture.completedFuture(null));
        port.publish(new OutboundRecord(topic, key, outboxId, UUID.fromString(AGGREGATE), traceparent));
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(producer).send(sent.capture());
        Mockito.clearInvocations(producer);
        return sent.getValue();
    }

    private void stubSend(CompletableFuture<RecordMetadata> future) {
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
    }

    private OutboundRecord row(long outboxId, String traceparent) {
        return new OutboundRecord("platform.message.accepted", RECORD_KEY, outboxId,
                UUID.fromString(AGGREGATE), traceparent);
    }

}