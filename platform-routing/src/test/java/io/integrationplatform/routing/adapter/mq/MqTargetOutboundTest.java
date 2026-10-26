package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Row 327: the production MQ producer parses the target's system settings; a bad row is PERMANENT, no secret leaks. */
class MqTargetOutboundTest {

    private static final String SECRET = "s3cr3t-pw";
    private static final Stage2Send SEND =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);

    private static MqTargetOutbound outbound(String settingsJson) {
        return new MqTargetOutbound(id -> Optional.ofNullable(settingsJson), id -> Optional.of(SECRET.toCharArray()),
                send -> Optional.of("a,b\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesHostPortQueueAndUser() {
        MqMessage message = outbound("{\"host\":\"mq.local\",\"port\":61616,\"queue\":\"q1\",\"user\":\"app\"}")
                .resolve(SEND).orElseThrow();
        assertThat(message.settings()).isEqualTo(new MqSettings("mq.local", 61616, "q1"));
        assertThat(message.user()).isEqualTo("app");
        assertThat(message.password()).containsExactly(SECRET.toCharArray());
    }

    @Test
    void missingHostIsPermanent() {
        assertPermanent("{\"port\":61616,\"queue\":\"q1\"}");
    }

    @Test
    void missingQueueIsPermanent() {
        assertPermanent("{\"host\":\"mq.local\",\"port\":61616}");
    }

    @Test
    void malformedJsonIsPermanent() {
        assertPermanent("{not json");
    }

    @Test
    void noSettingsRowResolvesEmpty() {
        assertThat(outbound(null).resolve(SEND)).isEmpty();
    }

    @Test
    void noCredentialInToStringOrFaultMessage() {
        MqMessage message = outbound("{\"host\":\"mq.local\",\"port\":61616,\"queue\":\"q1\",\"user\":\"app\"}")
                .resolve(SEND).orElseThrow();
        assertThat(message.toString()).doesNotContain(SECRET).doesNotContain("a,b");
        assertThatThrownBy(() -> outbound("{\"port\":1,\"user\":\"" + SECRET + "\"}").resolve(SEND))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
    }

    private static void assertPermanent(String json) {
        assertThatThrownBy(() -> outbound(json).resolve(SEND))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        e -> assertThat(e.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }
}
