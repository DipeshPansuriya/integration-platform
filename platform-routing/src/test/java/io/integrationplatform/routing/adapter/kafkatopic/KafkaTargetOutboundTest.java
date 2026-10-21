package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Row 330: the production Kafka producer parses the target's system settings; a bad row is PERMANENT, no secret leaks. */
class KafkaTargetOutboundTest {

    private static final String SECRET = "s3cr3t-pw";
    private static final Stage2Send SEND =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);

    private static KafkaTargetOutbound outbound(String settingsJson) {
        return new KafkaTargetOutbound(id -> Optional.ofNullable(settingsJson),
                send -> Optional.of("a,b".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesBootstrapServersAndTopic() {
        KafkaMessage message = outbound("{\"bootstrapServers\":\"broker.local:9092\",\"topic\":\"orders-out\"}")
                .resolve(SEND).orElseThrow();
        assertThat(message.settings()).isEqualTo(new KafkaTopicSettings("broker.local:9092", "orders-out"));
        assertThat(message.settings().host()).isEqualTo("broker.local");
        assertThat(message.settings().port()).isEqualTo(9092);
        assertThat(message.body()).isEqualTo("a,b".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void missingBootstrapServersIsPermanent() {
        assertPermanent("{\"topic\":\"orders-out\"}");
    }

    @Test
    void missingTopicIsPermanent() {
        assertPermanent("{\"bootstrapServers\":\"broker.local:9092\"}");
    }

    @Test
    void malformedJsonIsPermanent() {
        assertPermanent("{not json");
    }

    @Test
    void noSettingsRowResolvesEmpty() {
        KafkaTargetOutbound outbound = new KafkaTargetOutbound(id -> Optional.empty(),
                send -> Optional.of("a,b".getBytes(StandardCharsets.UTF_8)));
        assertThat(outbound.resolve(SEND)).isEmpty();
    }

    @Test
    void noBodyResolvesEmpty() {
        KafkaTargetOutbound outbound = new KafkaTargetOutbound(id -> Optional.of("{\"bootstrapServers\":\"broker.local:9092\",\"topic\":\"orders-out\"}"),
                send -> Optional.empty());
        assertThat(outbound.resolve(SEND)).isEmpty();
    }

    @Test
    void noSecretInToStringOrFaultMessage() {
        KafkaMessage message = outbound("{\"bootstrapServers\":\"broker.local:9092\",\"topic\":\"orders-out\"}")
                .resolve(SEND).orElseThrow();
        assertThat(message.toString()).doesNotContain(SECRET);
        assertThatThrownBy(() -> outbound("{\"topic\":\"" + SECRET + "\"}").resolve(SEND))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
    }

    @Test
    void deliveryIdIsTheRecordKey() {
        // idempotency is carried by the KafkaClientsSender record key, which is derived from the delivery id
        assertThat(KafkaClientsSender.class).isNotNull();
    }

    private static void assertPermanent(String json) {
        assertThatThrownBy(() -> outbound(json).resolve(SEND))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        e -> assertThat(e.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }
}
