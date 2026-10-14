package io.integrationplatform.routing.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

/**
 * T-191 (E8-F1-S3; ADR-005 Decisions 7 and 12): the relay producer carries the timeout and transient-retry
 * controls - {@code request.timeout.ms} 10 s, {@code delivery.timeout.ms} 30 s, a bounded {@code max.block.ms} so a
 * missing broker never blocks the relay thread for the client's 60 s default, and the idempotent producer
 * ({@code acks=all}, at most 5 in flight) whose own retries are the "transient failures only" retry.
 */
class KafkaPublishPortConfigurationTest {

    @Test
    void theRelayProducerCarriesTheTimeoutAndIdempotentRetryControls() {
        Map<String, Object> config = KafkaPublishPortConfiguration.producerConfig("broker:9092", "relay-1");

        assertThat(config)
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092")
                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "relay-1")
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000)
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }
}
