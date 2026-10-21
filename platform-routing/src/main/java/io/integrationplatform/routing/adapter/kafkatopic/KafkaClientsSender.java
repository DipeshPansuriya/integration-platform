package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/** T-148/T-330: the kafka-clients sender (already on the classpath via camel-kafka-starter, R12). One producer per
 * attempt, acked send, no reconnect: a failed attempt goes back to layer-1 redelivery, never retried here. The broker
 * address is pinned through the {@link DestinationGuard} before any producer is created, so a DNS rebind between the
 * check and the dial reaches nothing (SF-1). */
final class KafkaClientsSender implements KafkaSender {

    private final DestinationGuard guard;

    KafkaClientsSender() {
        this(null);
    }

    KafkaClientsSender(DestinationGuard guard) {
        this.guard = guard;
    }

    @Override
    public void send(KafkaTopicSettings settings, UUID deliveryId, byte[] body, java.time.Duration timeout)
            throws Exception {
        String bootstrapServers = settings.bootstrapServers();
        if (guard != null) {
            bootstrapServers = pinnedBootstrapServers(settings.bootstrapServers(), guard);
        }
        Properties props = producerProperties(bootstrapServers, timeout);
        ProducerRecord<byte[], byte[]> record = record(settings, deliveryId, body,
                io.integrationplatform.ingest.port.CorrelationContext.currentTraceparent());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            // metadata first: a broker or topic that cannot be reached fails HERE, before any record is handed
            // over - nothing was sent, so the call classes it TRANSIENT (a timeout later is AMBIGUOUS)
            producer.partitionsFor(settings.topic());
            try {
                producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException refused) {
                // the broker answered with a typed error: surface it unwrapped so the call can classify it
                if (refused.getCause() instanceof org.apache.kafka.common.errors.TimeoutException) {
                    // the record was handed over and its delivery expired unanswered: it may be on the topic
                    throw new java.util.concurrent.TimeoutException("no acknowledgement for the record");
                }
                if (refused.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw refused;
            }
        }
    }

    /** The producer's configuration for one attempt bounded by {@code timeout}. */
    static Properties producerProperties(String bootstrapServers, java.time.Duration timeout) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(timeout.toMillis()));
        // kafka-clients refuses delivery.timeout.ms < linger.ms + request.timeout.ms. The send timeout is the whole
        // bound of one attempt, so: no batching delay (linger 0), one request may use all of it, and the delivery
        // bound is exactly request + linger = the send timeout. A record unacknowledged at that point is AMBIGUOUS
        // (the Future.get below carries the same bound); the cluster-unreachable pre-check stays TRANSIENT.
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        return props;
    }

    /** The record: the delivery id as key (one stable identity per delivery for dedup and trace lookups) and the
     * W3C traceparent of the open journey as its header, so the target's consumer continues the trace. */
    static ProducerRecord<byte[], byte[]> record(KafkaTopicSettings settings, UUID deliveryId, byte[] body,
            String traceparent) {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(settings.topic(),
                deliveryId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), body);
        if (traceparent != null) {
            record.headers().add("traceparent", traceparent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return record;
    }

    /** Resolves each bootstrap server's host once and pins the producer to the resolved address (no re-resolution). */
    private static String pinnedBootstrapServers(String bootstrapServers, DestinationGuard guard)
            throws DestinationRefusedException, UnknownHostException {
        StringBuilder pinned = new StringBuilder();
        for (String server : bootstrapServers.split(",", -1)) {
            String trimmed = server.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int at = trimmed.lastIndexOf(':');
            String host = trimmed.substring(0, at).strip();
            int port = Integer.parseInt(trimmed.substring(at + 1).strip());
            CheckedDestination checked = guard.check(URI.create("http://" + host + ":" + port));
            if (pinned.length() > 0) {
                pinned.append(',');
            }
            pinned.append(checked.pinnedAddress().getHostAddress()).append(':').append(port);
        }
        return pinned.toString();
    }
}
