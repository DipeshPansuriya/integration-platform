package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E16 review blocker B1, no broker: the producer the sender builds must be constructible with the send timeouts the
 * platform really uses (5 s in production). kafka-clients refuses delivery.timeout.ms below
 * linger.ms + request.timeout.ms, and that refusal used to surface as a TRANSIENT failure of every KAFKA delivery.
 */
class KafkaClientsSenderConfigTest {

    @ParameterizedTest
    @ValueSource(longs = {1_000, 3_000, 5_000, 10_000, 15_000, 120_000})
    void theProducerIsConstructibleWithTheSendTimeout(long timeoutMs) {
        Properties props = KafkaClientsSender.producerProperties("127.0.0.1:1", Duration.ofMillis(timeoutMs));

        // the constructor validates the timeouts and opens no connection, so no broker is needed
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            assertThat(producer).isNotNull();
        }
    }

    @Test
    void theDeliveryBoundStaysAtTheSendTimeoutAndTheRequestBoundNeverExceedsIt() {
        Properties props = KafkaClientsSender.producerProperties("127.0.0.1:1", Duration.ofSeconds(5));

        long delivery = Long.parseLong(props.getProperty(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
        long request = Long.parseLong(props.getProperty(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG));
        long linger = Long.parseLong(props.getProperty(ProducerConfig.LINGER_MS_CONFIG));
        assertThat(request).isLessThanOrEqualTo(5_000);
        assertThat(linger).isZero();
        assertThat(delivery).isGreaterThanOrEqualTo(request + linger).isLessThanOrEqualTo(5_000);
    }
}
