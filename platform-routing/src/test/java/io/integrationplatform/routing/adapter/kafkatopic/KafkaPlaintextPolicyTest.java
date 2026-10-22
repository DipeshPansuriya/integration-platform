package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E16 Karan R1/R2/R9: clear text is an operator opt-in, never a tenant choice; a sender never runs without a guard. */
class KafkaPlaintextPolicyTest {

    private static final DestinationGuard GUARD = new DestinationGuard(AllowList.of(List.of("*.partner.example")),
            host -> List.of(InetAddress.getByName("93.184.216.34")));

    private static KafkaTopicSettings settings(String protocol) {
        boolean sasl = protocol.startsWith("SASL");
        return KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"k.partner.example:9092\",\"topic\":\"orders\","
                + "\"securityProtocol\":\"" + protocol + "\""
                + (sasl ? ",\"saslMechanism\":\"SCRAM-SHA-256\",\"username\":\"svc\"" : "") + "}");
    }

    private static void send(KafkaClientsSender sender, String protocol) throws Exception {
        sender.send(settings(protocol), "pw".toCharArray(), UUID.randomUUID(), new byte[] {1}, Duration.ofSeconds(2));
    }

    private static KafkaClientsSender sender(Boolean allowPlaintext, List<Properties> built) {
        Function<Properties, Producer<byte[], byte[]>> factory = props -> {
            built.add(props);
            return new MockProducer<>(Cluster.empty(), true, null, new ByteArraySerializer(),
                    new ByteArraySerializer());
        };
        return allowPlaintext == null ? new KafkaClientsSender(GUARD, null, factory)
                : new KafkaClientsSender(GUARD, null, allowPlaintext, factory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAINTEXT", "SASL_PLAINTEXT"})
    @Tag("security")
    void clearTextIsRefusedAtSendByDefaultAndNoProducerIsBuilt(String protocol) {
        List<Properties> built = new ArrayList<>();

        assertThatThrownBy(() -> send(sender(null, built), protocol)).isInstanceOf(KafkaSecurityException.class);
        assertThatThrownBy(() -> send(sender(false, built), protocol)).isInstanceOf(KafkaSecurityException.class)
                .hasMessageContaining("kafka-allow-plaintext");

        assertThat(built).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSL", "SASL_SSL"})
    void tlsNeverNeedsTheSwitch(String protocol) {
        List<Properties> built = new ArrayList<>();
        // the MockProducer cluster is empty, so the send may end in a Kafka error AFTER the producer was built: that
        // is all this test asks (the protocol passed the policy)
        try {
            send(sender(false, built), protocol);
        } catch (Exception afterPolicy) {
            assertThat(afterPolicy).isNotInstanceOf(KafkaSecurityException.class);
        }
        assertThat(built).hasSize(1);
    }

    @Test
    void theOperatorSwitchAllowsClearText() {
        List<Properties> built = new ArrayList<>();
        try {
            send(sender(true, built), "PLAINTEXT");
        } catch (Exception afterPolicy) {
            assertThat(afterPolicy).isNotInstanceOf(KafkaSecurityException.class);
        }
        assertThat(built).hasSize(1);
    }

    @Test
    @Tag("security")
    void aSenderWithoutAGuardCannotBeBuilt() {
        assertThatThrownBy(() -> new KafkaClientsSender(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KafkaClientsSender(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KafkaClientsSender(null, null, props -> null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KafkaClientsSender(null, null, true, props -> null))
                .isInstanceOf(NullPointerException.class);
    }
}
