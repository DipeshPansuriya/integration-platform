package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

/**
 * E16 T-148 hardening, no Docker: the record carries the delivery id as key and the W3C traceparent as its header
 * (the journey continues onto the target's topic), and an ack that never arrived is AMBIGUOUS - the record may be
 * on the topic (ADR-010 section 5: a timeout after the send is never "failed").
 */
class KafkaTopicHardeningTest {

    static final UUID DELIVERY = UUID.randomUUID();
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    static final KafkaTopicSettings SETTINGS =
            KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"localhost:9092\",\"topic\":\"orders-out\"}");

    @Test
    void theRecordIsKeyedByTheDeliveryIdAndCarriesTheTraceparent() {
        ProducerRecord<byte[], byte[]> record = KafkaClientsSender.record(SETTINGS, DELIVERY, new byte[] {7},
                TRACEPARENT);

        assertThat(record.topic()).isEqualTo("orders-out");
        assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo(DELIVERY.toString());
        assertThat(record.value()).containsExactly(7);
        assertThat(record.headers().headers("traceparent")).singleElement()
                .satisfies(h -> assertThat(new String(h.value(), StandardCharsets.UTF_8)).isEqualTo(TRACEPARENT));
    }

    @Test
    void noJourneyMeansNoHeader() {
        ProducerRecord<byte[], byte[]> record = KafkaClientsSender.record(SETTINGS, DELIVERY, new byte[0], null);

        assertThat(record.headers().headers("traceparent")).isEmpty();
    }

    @Test
    void anAckThatNeverArrivedIsAmbiguousNotTransient() {
        KafkaSender timedOut = (settings, id, body, timeout) -> {
            throw new TimeoutException("no ack within the timeout");
        };
        KafkaTopicTargetCall call = new KafkaTopicTargetCall(
                send -> Optional.of(new KafkaMessage(SETTINGS, new byte[1])), timedOut,
                KafkaTopicTargetCallControlsTest.guard(), Set.of("platform-work"), CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP, Duration.ofSeconds(5));

        assertThatThrownBy(() -> call.call(new Stage2Send(DELIVERY, UUID.randomUUID(), null, UUID.randomUUID(), false)))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.AMBIGUOUS));
    }
}
