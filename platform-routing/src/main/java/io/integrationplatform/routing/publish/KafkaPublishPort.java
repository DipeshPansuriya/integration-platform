package io.integrationplatform.routing.publish;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * The Kafka {@link PublishPort} adapter of the outbox relay (T-110, E8-F1-S1; ADR-011 Decision 7): plain
 * {@code kafka-clients} (here through the {@code camel-kafka} starter's dependency - never spring-kafka, R12)
 * wrapped by the one narrow port the relay calls, so the relay keeps its vendor-client architecture test.
 *
 * <p>{@code publish} is fire-and-forget - it sends the {@link ProducerRecord} and returns at once; {@link
 * #acknowledge} waits for the broker's completion for the timeout and answers {@code false} on a failure or a
 * timeout, which the relay turns into "leave the row unpublished for a later cycle" (at-least-once, RPO zero).
 *
 * <p>Record content (ADR-011 Decision 7): the key is the stored record key - SHA-256 of (tenant, interface,
 * business key), E8-C2 - so same-key records share one partition and keep the publish order; the value is the
 * ids-only {@code {"v":1,"type":...,"id":...}} shape the consumer side resolves; and the row's stored {@code
 * traceparent} is carried as the record's ONLY header (T-112, E8-C5), or no header when the row has none.
 */
public final class KafkaPublishPort implements PublishPort {

    private static final String PLATFORM_TOPIC_PREFIX = "platform.";

    /** The only header a record carries: the outbox row's stored W3C trace context (E8-C5). */
    static final String TRACEPARENT_HEADER = "traceparent";

    private final Producer<String, String> producer;

    public KafkaPublishPort(Producer<String, String> producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    @Override
    public PublishReceipt publish(OutboundRecord record) {
        Future<org.apache.kafka.clients.producer.RecordMetadata> sent = producer.send(producerRecord(record));
        return new KafkaPublishReceipt(record.outboxId(), sent);
    }

    @Override
    public boolean acknowledge(PublishReceipt receipt, Duration timeout) {
        KafkaPublishReceipt kafka = (KafkaPublishReceipt) receipt;
        try {
            kafka.sent().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            return false;
        } catch (ExecutionException rejected) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The broker record: ids-only value (ADR-011 Decision 7), the stored record key, and the traceparent as
     * the only header when the row stored one (a row without a trace carries no header - the "only header"
     * contract is about which headers exist, never about inventing one).
     */
    private ProducerRecord<String, String> producerRecord(OutboundRecord record) {
        ProducerRecord<String, String> sent = new ProducerRecord<>(record.topic(), record.key(), recordValue(record));
        if (record.traceparent() != null) {
            sent.headers().add(TRACEPARENT_HEADER, record.traceparent().getBytes(StandardCharsets.UTF_8));
        }
        return sent;
    }

    /** {@code {"v":1,"type":"message.accepted","id":"<uuid>"}} - topic without the {@code platform.} prefix. */
    private String recordValue(OutboundRecord record) {
        String topic = record.topic();
        String type = topic.startsWith(PLATFORM_TOPIC_PREFIX)
                ? topic.substring(PLATFORM_TOPIC_PREFIX.length())
                : topic;
        return "{\"v\":1,\"type\":\"" + type + "\",\"id\":\"" + record.aggregateId() + "\"}";
    }

    /** The receipt of one sent record: the row it was read from plus the broker's pending promise. */
    private record KafkaPublishReceipt(long outboxId, Future<org.apache.kafka.clients.producer.RecordMetadata> sent)
            implements PublishReceipt {
    }
}