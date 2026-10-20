package io.integrationplatform.routing.adapter.kafkatopic;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/** T-148: the kafka-clients sender (already on the classpath via camel-kafka-starter, R12). One producer per
 * attempt, acked send, no reconnect: a failed attempt goes back to layer-1 redelivery, never retried here. */
final class KafkaClientsSender implements KafkaSender {

    @Override
    public void send(KafkaTopicSettings settings, UUID deliveryId, byte[] body, java.time.Duration timeout)
            throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(timeout.toMillis()));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        // the delivery id is the record key: one stable identity per delivery for dedup and trace lookups
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(settings.topic(),
                deliveryId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), body);
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            try {
                producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException refused) {
                // the broker answered with a typed error: surface it unwrapped so the call can classify it
                if (refused.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw refused;
            }
        }
    }
}
