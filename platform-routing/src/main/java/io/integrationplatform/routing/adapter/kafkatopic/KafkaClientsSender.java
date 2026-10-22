package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/** T-148/T-330: the kafka-clients sender (already on the classpath via camel-kafka-starter, R12). One producer per
 * attempt, acked send, no reconnect: a failed attempt goes back to layer-1 redelivery, never retried here.
 *
 * <p>Destination guard on EVERY connection (SF-1, E16 F7): each bootstrap server goes through the
 * {@link DestinationGuard} before any producer is created - for a clear-text protocol the producer is pinned to the
 * checked address (a DNS rebind between check and dial reaches nothing); for TLS the name is kept so the certificate's
 * hostname check (always on, {@link KafkaSecurity}) covers a rebind - and the brokers the cluster then ADVERTISES for
 * the topic are guarded the same way ({@link AdvertisedBrokers}) before the record is handed over.
 *
 * <p>Security (E16): protocol, SASL and truststore from the settings, the SASL password from the vault copy the call
 * passes in; it reaches only the producer's JAAS property and is never logged here. */
final class KafkaClientsSender implements KafkaSender {

    private final DestinationGuard guard;
    private final Path truststoreDir;
    private final boolean allowPlaintext;
    private final Function<Properties, Producer<byte[], byte[]>> producers;

    KafkaClientsSender(DestinationGuard guard) {
        this(guard, null);
    }

    /** @param truststoreDir the operator's directory of truststore files; null = a system naming a truststore is refused */
    KafkaClientsSender(DestinationGuard guard, Path truststoreDir) {
        this(guard, truststoreDir, false, KafkaProducer::new);
    }

    /** TLS only; the producer factory is a seam so the guards are testable without a broker. */
    KafkaClientsSender(DestinationGuard guard, Path truststoreDir,
            Function<Properties, Producer<byte[], byte[]>> producers) {
        this(guard, truststoreDir, false, producers);
    }

    /** The production wiring: kafka-clients' own producer. */
    KafkaClientsSender(DestinationGuard guard, Path truststoreDir, boolean allowPlaintext) {
        this(guard, truststoreDir, allowPlaintext, KafkaProducer::new);
    }

    /**
     * @param guard          never null (R9): a sender without a guard would skip every check
     * @param allowPlaintext the operator's {@code platform.config.destinations.kafka-allow-plaintext}; false (default)
     *                       refuses PLAINTEXT and SASL_PLAINTEXT at send (R1/R2)
     */
    KafkaClientsSender(DestinationGuard guard, Path truststoreDir, boolean allowPlaintext,
            Function<Properties, Producer<byte[], byte[]>> producers) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard");
        this.truststoreDir = truststoreDir;
        this.allowPlaintext = allowPlaintext;
        this.producers = java.util.Objects.requireNonNull(producers, "producers");
    }

    @Override
    public void send(KafkaTopicSettings settings, UUID deliveryId, byte[] body, java.time.Duration timeout)
            throws Exception {
        send(settings, null, deliveryId, body, timeout);
    }

    @Override
    public void send(KafkaTopicSettings settings, char[] saslPassword, UUID deliveryId, byte[] body,
            java.time.Duration timeout) throws Exception {
        KafkaSecurityProtocol.requireAllowed(settings.protocol(), allowPlaintext);
        String bootstrapServers = guardedBootstrapServers(settings, guard);
        Properties props = producerProperties(bootstrapServers, timeout, settings, saslPassword, truststoreDir);
        AtomicReference<Exception> vetoed = new AtomicReference<>();
        if (settings.protocol().tls()) {
            // R1: the one hook kafka-clients has - the TLS engine factory is asked for an engine for EVERY node the
            // client dials, after the TCP connect and before the ClientHello; it refuses a node the guard refuses
            GuardedSslEngineFactory.install(props, guard, vetoed);
        }
        ProducerRecord<byte[], byte[]> record = record(settings, deliveryId, body,
                io.integrationplatform.ingest.port.CorrelationContext.currentTraceparent());
        try (Producer<byte[], byte[]> producer = producers.apply(props)) {
            // metadata first: a broker or topic that cannot be reached fails HERE, before any record is handed
            // over - nothing was sent, so the call classes it TRANSIENT (a timeout later is AMBIGUOUS)
            List<PartitionInfo> partitions;
            try {
                partitions = producer.partitionsFor(settings.topic());
            } catch (RuntimeException failed) {
                throwIfVetoed(vetoed);
                throw failed;
            }
            // the cluster told us which brokers to dial: guard them before the record is handed over (F7). This is
            // the LATE check (the client may already have dialled); the TLS veto above and the operator's egress
            // allow-list are the early ones.
            AdvertisedBrokers.require(partitions, guard);
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

    /** A node the TLS veto refused is a destination refusal (PERMANENT), whatever timeout the client reported. */
    private static void throwIfVetoed(AtomicReference<Exception> vetoed) throws Exception {
        Exception veto = vetoed.get();
        if (veto != null) {
            throw veto;
        }
    }

    /** The producer's configuration for one attempt bounded by {@code timeout} (no security: see the overload). */
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

    /** E16: the timeouts above plus the system's protocol, SASL and truststore ({@link KafkaSecurity}). */
    static Properties producerProperties(String bootstrapServers, java.time.Duration timeout,
            KafkaTopicSettings settings, char[] saslPassword, Path truststoreDir) {
        Properties props = producerProperties(bootstrapServers, timeout);
        KafkaSecurity.apply(props, settings, saslPassword, truststoreDir);
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

    /** Runs each bootstrap server through the guard; clear-text protocols are pinned to the resolved address (no
     * re-resolution), TLS ones keep the host name so the certificate's hostname check applies to the connection. */
    private static String guardedBootstrapServers(KafkaTopicSettings settings, DestinationGuard guard)
            throws DestinationRefusedException, UnknownHostException {
        boolean pin = !settings.protocol().tls();
        StringBuilder out = new StringBuilder();
        for (String server : settings.bootstrapServers().split(",", -1)) {
            String trimmed = server.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int at = trimmed.lastIndexOf(':');
            String host = trimmed.substring(0, at).strip();
            int port = Integer.parseInt(trimmed.substring(at + 1).strip());
            CheckedDestination checked = guard.check(URI.create("http://" + host + ":" + port));
            if (out.length() > 0) {
                out.append(',');
            }
            String dial = pin ? checked.pinnedAddress().getHostAddress() : host;
            out.append(dial.contains(":") && !dial.startsWith("[") ? "[" + dial + "]" : dial) // IPv6 literal
                    .append(':').append(port);
        }
        return out.toString();
    }
}
