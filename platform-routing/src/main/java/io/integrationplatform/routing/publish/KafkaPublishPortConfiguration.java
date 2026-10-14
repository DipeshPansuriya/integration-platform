package io.integrationplatform.routing.publish;

import io.integrationplatform.ingest.port.PublishPort;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-110 wiring of the outbox relay's Kafka publish adapter: the relay's {@link PublishPort} is this Kafka
 * producer when the deployment runs a Kafka backbone (E8-F1-S1) - and is not wired at all when it does not,
 * so an instance that only serves the console starts without it (the adapter's broker address comes from
 * {@code config/routing-kafka.yml}, R07: an environment-variable NAME, no default).
 *
 * <p>The condition is the deployment's own environment variable, read directly from the environment: the yaml
 * binds it as the broker address, so wiring and configuration agree the same deployment decision. {@code
 * client.id} names this publisher in the operators' lag metrics (ADR-010 section 9).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
class KafkaPublishPortConfiguration {

    @Bean(destroyMethod = "close")
    Producer<String, String> kafkaPublishProducer(
            @Value("${platform.routing.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${platform.routing.kafka.client-id}") String clientId) {
        return new KafkaProducer<>(producerConfig(bootstrapServers, clientId));
    }

    /**
     * The relay producer's settings. T-191 (E8-F1-S3; ADR-005 Decisions 7 and 12): every blocking client call
     * is bounded - {@code request.timeout.ms} 10 s, {@code delivery.timeout.ms} 30 s, a bounded {@code
     * max.block.ms} so a missing broker never blocks the relay thread for the client's 60 s default - and the
     * idempotent producer ({@code acks=all}, at most 5 in flight) retries transient broker failures itself.
     */
    static Map<String, Object> producerConfig(String bootstrapServers, String clientId) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return config;
    }

    @Bean
    PublishPort kafkaPublishPort(Producer<String, String> producer) {
        return new KafkaPublishPort(producer);
    }
}