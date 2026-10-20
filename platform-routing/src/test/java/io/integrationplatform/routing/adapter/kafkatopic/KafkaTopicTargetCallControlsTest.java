package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;

/**
 * T-148: the five controls around a Kafka-topic send, no Docker. The TRANSIENT/PERMANENT classification is the
 * contract layer-1 consumes for redelivery and dead-lettering (proved in the MQ lane's controls test).
 */
class KafkaTopicTargetCallControlsTest {

    private static final String HOST = "localhost";
    private static final Set<String> PLATFORM_TOPICS = Set.of("platform-work", "platform-poison");
    private static final byte[] BODY = "order-1,pay".getBytes(StandardCharsets.UTF_8);

    record Sent(String topic, String key, byte[] body) {
    }

    static DestinationGuard guard() {
        return new DestinationGuard(AllowList.of(List.of(HOST)), host -> List.of(InetAddress.getByName("127.0.0.1")));
    }

    static KafkaTopicSettings settings(String topic) {
        return KafkaTopicSettings.fromJson(
                "{\"bootstrapServers\":\"localhost:9092\",\"topic\":\"" + topic + "\"}");
    }

    static KafkaOutbound outbound(KafkaTopicSettings settings) {
        return send -> Optional.of(new KafkaMessage(settings, BODY.clone()));
    }

    static Stage2Send send(UUID endpoint) {
        return new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "order-1", endpoint, false);
    }

    static KafkaTopicTargetCall call(KafkaOutbound outbound, KafkaSender sender,
            CircuitBreakerRegistry breakers, ObservationRegistry observations) {
        return new KafkaTopicTargetCall(outbound, sender, guard(), PLATFORM_TOPICS, breakers,
                new SimpleMeterRegistry(), observations, Duration.ofSeconds(5));
    }

    @Test
    void deliveredRecordLandsOnTheTargetsOwnTopicNotAPlatformTopic() {
        List<Sent> sent = new ArrayList<>();
        KafkaSender sender = (st, id, body, timeout) -> sent.add(
                new Sent(st.topic(), id.toString(), body.clone())); // clone: the call zeroes its copy afterwards
        call(outbound(settings("orders-out")), sender, CircuitBreakerRegistry.ofDefaults(),
                ObservationRegistry.NOOP).call(send(UUID.randomUUID()));
        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.topic()).isEqualTo("orders-out");
            assertThat(s.topic()).isNotIn(PLATFORM_TOPICS);
        });
    }

    @Test
    void platformOwnedTargetTopicIsRefusedBeforeAnySend() {
        AtomicBoolean attempted = new AtomicBoolean();
        KafkaSender sender = (st, id, body, timeout) -> attempted.set(true);
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        UUID endpoint = UUID.randomUUID();
        assertThatThrownBy(() -> call(outbound(settings("platform-work")), sender, breakers,
                ObservationRegistry.NOOP).call(send(endpoint)))
                        .isInstanceOf(TargetFaultException.class)
                        .isNotInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThat(attempted).as("a refused topic is never dialled").isFalse();
        assertThat(breakers.circuitBreaker("kafka:" + endpoint, KafkaTopicTargetCall.BREAKER).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void brokerRefusalIsPermanentAndDoesNotTripTheBreaker() {
        KafkaSender sender = (st, id, body, timeout) -> {
            throw new TopicAuthorizationException("not authorized to publish");
        };
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        UUID endpoint = UUID.randomUUID();
        assertThatThrownBy(() -> call(outbound(settings("orders-out")), sender, breakers,
                ObservationRegistry.NOOP).call(send(endpoint)))
                        .isInstanceOf(TargetFaultException.class)
                        .isNotInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThat(breakers.circuitBreaker("kafka:" + endpoint, KafkaTopicTargetCall.BREAKER).getState())
                .as("the broker answered: a refusal is not a broker fault").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unreachableBrokerIsTransientUntilTheBreakerOpensAndSkipsTheSixth() {
        AtomicInteger resolved = new AtomicInteger();
        KafkaSender sender = (st, id, body, timeout) -> {
            throw new KafkaException("connection refused");
        };
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        UUID endpoint = UUID.randomUUID();
        KafkaTopicTargetCall call = call(send -> {
            resolved.incrementAndGet();
            return outbound(settings("orders-out")).resolve(send);
        }, sender, breakers, ObservationRegistry.NOOP);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> call.call(send(endpoint)))
                    .as("attempt " + attempt).isInstanceOf(TargetFaultException.TransientFaultException.class);
        }
        assertThat(breakers.circuitBreaker("kafka:" + endpoint, KafkaTopicTargetCall.BREAKER).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> call.call(send(endpoint))).isInstanceOfSatisfying(
                TargetFaultException.TransientFaultException.class,
                open -> assertThat(open).hasMessageContaining("is open"));
        assertThat(resolved.get()).as("the open breaker never resolves the delivery").isEqualTo(5);
    }

    @Test
    void aSendThatTimesOutIsTransient() {
        KafkaSender sender = (st, id, body, timeout) -> {
            throw new TimeoutException("no ack within the timeout");
        };
        assertThatThrownBy(() -> call(outbound(settings("orders-out")), sender,
                CircuitBreakerRegistry.ofDefaults(), ObservationRegistry.NOOP).call(send(UUID.randomUUID())))
                        .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    void everyAttemptIsMeteredAndTraced() {
        List<String> seen = new ArrayList<>();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                seen.add(context.getName() + " outcome=" + context.getLowCardinalityKeyValue("outcome").getValue());
            }
        });
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KafkaSender sender = (st, id, body, timeout) -> {
            throw new KafkaException("connection refused");
        };
        assertThatThrownBy(() -> new KafkaTopicTargetCall(outbound(settings("orders-out")), sender, guard(),
                PLATFORM_TOPICS, CircuitBreakerRegistry.ofDefaults(), meters, observations, Duration.ofSeconds(5))
                        .call(send(UUID.randomUUID())))
                .as("the transient fault is metered and traced, then propagates to the stage-2 retry path")
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThat(meters.get("kafka.target.send").tag("target_type", "KAFKA").tag("outcome", "TRANSIENT").timer()
                .count()).isEqualTo(1);
        assertThat(meters.get("kafka.target.outcome").tag("target_type", "KAFKA").tag("outcome", "TRANSIENT").counter()
                .count()).isEqualTo(1.0);
        assertThat(seen).containsExactly("kafka.target.send outcome=TRANSIENT");
    }
}
