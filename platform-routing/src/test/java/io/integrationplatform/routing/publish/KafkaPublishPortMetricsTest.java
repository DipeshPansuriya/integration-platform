package io.integrationplatform.routing.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;

/**
 * E8 tail (T-191 observability, ADR-005 Decision 12 "publish latency and errors"): every relay publish the Kafka
 * port acknowledges is counted by outcome ({@code platform.publish.records}, success/failure, tagged by topic and
 * failure class) and timed send-to-answer ({@code platform.publish.latency}); no tag carries a tenant, a record key
 * or an aggregate id - the cardinality stays topics x outcomes x failure classes.
 */
class KafkaPublishPortMetricsTest {

    private static final String TOPIC = "platform.message.accepted";
    private static final String RECORD_KEY = "1a2b3c4d5e6f7081a2b3c4d5e6f7081a2b3c4d5e6f7081a2b3c4d5e6f7081a2b3";
    private static final String AGGREGATE = "b16a5c9d-5e5f-4a1b-9c2d-3e4f5a6b7c8d";

    @SuppressWarnings("unchecked")
    private final Producer<String, String> producer = mock(Producer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final KafkaPublishPort port = new KafkaPublishPort(producer, meters);

    @Test
    void anAcknowledgedPublishCountsOneSuccessAndTimesIt() {
        stubSend(CompletableFuture.completedFuture(null));

        assertThat(port.acknowledge(port.publish(row(1)), Duration.ofSeconds(5))).isTrue();

        assertThat(records("success", "none").count()).as("one success on the topic").isEqualTo(1.0);
        assertThat(latency("success").count()).as("the send-to-answer latency is recorded").isEqualTo(1);
    }

    @Test
    void aBrokerRejectionCountsOneFailureTaggedByItsFailureClass() {
        CompletableFuture<RecordMetadata> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new RecordTooLargeException("too large"));
        stubSend(rejected);

        assertThat(port.acknowledge(port.publish(row(2)), Duration.ofSeconds(5))).isFalse();

        assertThat(records("failure", "RecordTooLargeException").count()).isEqualTo(1.0);
        assertThat(latency("failure").count()).isEqualTo(1);
        assertThat(meters.find(KafkaPublishPort.RECORDS_COUNTER).tag("outcome", "success").counter())
                .as("no success is counted for a rejected record").isNull();
    }

    @Test
    void anAnswerMissingTheTimeoutCountsOneAckTimeoutFailure() {
        stubSend(new CompletableFuture<>());

        assertThat(port.acknowledge(port.publish(row(3)), Duration.ofMillis(20))).isFalse();

        assertThat(records("failure", "ack_timeout").count()).isEqualTo(1.0);
    }

    @Test
    void aSendThatThrowsCountsOneFailureAndStillThrows() {
        when(producer.send(any(ProducerRecord.class))).thenThrow(new KafkaException("producer closed"));

        assertThatThrownBy(() -> port.publish(row(4))).isInstanceOf(KafkaException.class);

        assertThat(records("failure", "KafkaException").count()).isEqualTo(1.0);
    }

    @Test
    void noTagCarriesATenantARecordKeyOrAnAggregateId() {
        stubSend(CompletableFuture.completedFuture(null));
        port.acknowledge(port.publish(row(5)), Duration.ofSeconds(5));
        CompletableFuture<RecordMetadata> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new RecordTooLargeException("too large"));
        stubSend(rejected);
        port.acknowledge(port.publish(row(6)), Duration.ofSeconds(5));

        Set<String> keys = meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .map(tag -> tag.getKey()).collect(Collectors.toSet());
        Set<String> values = meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .map(tag -> tag.getValue()).collect(Collectors.toSet());
        assertThat(keys).as("only bounded tags").containsOnly("topic", "outcome", "failure");
        assertThat(values).doesNotContain(RECORD_KEY, AGGREGATE);
        assertThat(meters.getMeters().stream().map(Meter::getId).map(Meter.Id::getName).collect(Collectors.toSet()))
                .containsOnly(KafkaPublishPort.RECORDS_COUNTER, KafkaPublishPort.LATENCY_TIMER);
    }

    // ---------- helpers ----------

    private Counter records(String outcome, String failure) {
        Counter counter = meters.find(KafkaPublishPort.RECORDS_COUNTER)
                .tags("topic", TOPIC, "outcome", outcome, "failure", failure).counter();
        assertThat(counter).as("the %s/%s counter is registered", outcome, failure).isNotNull();
        return counter;
    }

    private Timer latency(String outcome) {
        Timer timer = meters.find(KafkaPublishPort.LATENCY_TIMER).tags("topic", TOPIC, "outcome", outcome).timer();
        assertThat(timer).as("the %s latency timer is registered", outcome).isNotNull();
        return timer;
    }

    private void stubSend(CompletableFuture<RecordMetadata> future) {
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
    }

    private static OutboundRecord row(long outboxId) {
        return new OutboundRecord(TOPIC, RECORD_KEY, outboxId, UUID.fromString(AGGREGATE), null);
    }
}
