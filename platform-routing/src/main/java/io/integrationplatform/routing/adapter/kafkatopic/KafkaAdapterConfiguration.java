package io.integrationplatform.routing.adapter.kafkatopic;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.SocketDialer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-148: the Kafka-topic adapter's beans - the "KAFKA" test-connection check (same MqConnectionCheck interface
 * the checks map holds) and the production target call. Gated by its own property so no existing context changes
 * until the lead wires the checks map and the TargetCallRegistry entry; the gate flips with the integration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_TOPIC_TARGET_ENABLED")
public class KafkaAdapterConfiguration {

    @Bean
    MqConnectionCheck kafkaTopicConnectionCheck(DestinationGuard guard, SocketDialer dialer) {
        KafkaBrokerProbe probe = new SocketKafkaBrokerProbe(guard, dialer);
        return (systemId, settingsJson) -> new KafkaTopicTestConnection(
                id -> Optional.of(KafkaTopicSettings.fromJson(settingsJson)), guard, probe, Duration.ofSeconds(5))
                .test(systemId);
    }

    @Bean
    KafkaTopicTargetCall kafkaTopicTargetCall(KafkaOutbound outbound, DestinationGuard guard,
            @Value("${IPASS_KAFKA_PLATFORM_TOPICS}") String platformTopics,
            ObjectProvider<CircuitBreakerRegistry> breakers, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        return new KafkaTopicTargetCall(outbound, new KafkaClientsSender(), guard, topics(platformTopics),
                breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), Duration.ofSeconds(5));
    }

    private static Set<String> topics(String csv) {
        if (csv == null || csv.isBlank()) {
            // fail closed (review T-148): with no platform topics listed the refusal protects nothing
            throw new IllegalStateException("IPASS_KAFKA_PLATFORM_TOPICS must list the platform's own topics");
        }
        return Stream.of(csv.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
