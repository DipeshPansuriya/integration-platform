package io.integrationplatform.routing.e8.duplicate;

import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import io.integrationplatform.routing.topics.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * The {@link PublishPort} this suite wires the real relay to while the product's Kafka adapter is not integrated
 * yet (lane B, T-110): a real kafka-clients 4.3.1 {@link KafkaProducer} against the real harness broker on the
 * pinned {@link Topics}, carrying the record the relay handed over - the row's stored {@code record_key} as the
 * key, ids only as the value ({@code {"v", "type", "id"}}, ADR-011 Decision 7) and the row's stored
 * {@code traceparent} as the only header. It implements exactly the port contract, so once lane B's adapter is
 * integrated arjun-fullstack can swap this stand-in for it and the scenario proves the product path end to end.
 */
final class E8TestKafkaPublishPort implements PublishPort {

    /** The record value shape of ADR-011 Decision 7: ids only, no payload, no tenant id. */
    static final String ADRIA_VERSION = "1";

    private final KafkaProducer<byte[], byte[]> producer;

    E8TestKafkaPublishPort(String bootstrapServers) {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000);
        this.producer = new KafkaProducer<>(producerProps);
    }

    @Override
    public PublishReceipt publish(io.integrationplatform.ingest.port.OutboundRecord record) {
        ProducerRecord<byte[], byte[]> wire = new ProducerRecord<>(record.topic(),
                record.key().getBytes(StandardCharsets.UTF_8), idsOnlyValue(record).getBytes(StandardCharsets.UTF_8));
        if (record.traceparent() != null) {
            // The trace survives the outbox hop (E8-C5): the row's stored traceparent, and nothing else.
            wire.headers().add("traceparent", record.traceparent().getBytes(StandardCharsets.US_ASCII));
        }
        try {
            producer.send(wire).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the record did not reach the real broker: " + e.getMessage(), e);
        }
        return record::outboxId;
    }

    @Override
    public boolean acknowledge(PublishReceipt receipt, Duration timeout) {
        // every publish waited for the broker's own acknowledgement of the record content above (acks all)
        producer.flush();
        return true;
    }

    static String idsOnlyValue(io.integrationplatform.ingest.port.OutboundRecord record) {
        return idsOnlyMessage(record.topic(), record.aggregateId());
    }

    /** The ids-only value of ADR-011 Decision 7, exactly as the wire must show it. */
    static String idsOnlyMessage(String topic, UUID id) {
        return "{\"v\":" + ADRIA_VERSION + ",\"type\":\"" + topic + "\",\"id\":\"" + id + "\"}";
    }

    static Properties consumerProps(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", bootstrapServers);
        props.setProperty("group.id", groupId + "-" + UUID.randomUUID());
        props.setProperty("auto.offset.reset", "earliest");
        props.setProperty("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        return props;
    }
}