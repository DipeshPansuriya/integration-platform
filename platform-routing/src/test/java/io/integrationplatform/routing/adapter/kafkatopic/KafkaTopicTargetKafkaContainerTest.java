package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.CorrelationContext;
import io.integrationplatform.routing.kafka.KafkaHarness;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-148 against a real Kafka broker as the target (E16-F1-S1; E16-C6): the record lands on the target topic keyed
 * by the delivery id with the journey's traceparent as its header, a platform topic is refused before anything is
 * sent, and a target cluster that is down is TRANSIENT (nothing was handed over, 0 records lost).
 */
@Tag("container")
class KafkaTopicTargetKafkaContainerTest {

    private static final Set<String> PLATFORM_TOPICS = Set.of("platform.message.accepted", "platform.delivery.due",
            "platform.poison");
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private static String bootstrap() {
        return KafkaHarness.get().bootstrapServers();
    }

    private static int portOf(String servers) {
        return Integer.parseInt(servers.substring(servers.lastIndexOf(':') + 1));
    }

    private static String hostOf(String servers) {
        return servers.substring(0, servers.lastIndexOf(':')).replace("PLAINTEXT://", "");
    }

    private KafkaTopicTargetCall call(String servers, String topic, byte[] body, Duration timeout) {
        String host = hostOf(servers);
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of(host)),
                name -> List.of(InetAddress.getLoopbackAddress()), List.of(host), List.of(portOf(servers)));
        KafkaTopicSettings settings = new KafkaTopicSettings(servers.replace("PLAINTEXT://", ""), topic, "PLAINTEXT", null, null, null);
        return new KafkaTopicTargetCall(send -> Optional.of(new KafkaMessage(settings, body.clone())),
                new KafkaClientsSender(guard, null, true), guard, PLATFORM_TOPICS, CircuitBreakerRegistry.ofDefaults(), meters,
                ObservationRegistry.NOOP, timeout);
    }

    private static String createTopic(String name) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get();
        }
        return name;
    }

    private static List<ConsumerRecord<byte[], byte[]>> read(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.currentTimeMillis() + 10_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    @Test
    void theRecordLandsOnTheTargetTopicKeyedByTheDeliveryIdWithTheTraceparent() throws Exception {
        String topic = createTopic("orders-out-" + UUID.randomUUID().toString().substring(0, 8));
        UUID deliveryId = UUID.randomUUID();
        byte[] body = "125-0001,3\r\n".getBytes(StandardCharsets.UTF_8);

        try (CorrelationContext.Scope journey = CorrelationContext.open(TRACEPARENT)) {
            call(bootstrap(), topic, body, Duration.ofSeconds(15))
                    .call(new Stage2Send(deliveryId, UUID.randomUUID(), null, UUID.randomUUID(), false));
        }

        List<ConsumerRecord<byte[], byte[]>> records = read(topic);
        assertThat(records).hasSize(1);
        ConsumerRecord<byte[], byte[]> record = records.get(0);
        assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo(deliveryId.toString());
        assertThat(record.value()).isEqualTo(body);
        assertThat(new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo(TRACEPARENT);
        assertThat(meters.get("kafka.target.outcome").tag("outcome", "SUCCESS").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aPlatformTopicIsRefusedAndNothingReachesTheBroker() throws Exception {
        String topic = createTopic("platform-guard-" + UUID.randomUUID().toString().substring(0, 8));
        Set<String> platform = Set.of(topic);
        String servers = bootstrap();
        String host = hostOf(servers);
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of(host)),
                name -> List.of(InetAddress.getLoopbackAddress()), List.of(host), List.of(portOf(servers)));
        KafkaTopicTargetCall call = new KafkaTopicTargetCall(
                send -> Optional.of(new KafkaMessage(new KafkaTopicSettings(servers, topic, "PLAINTEXT", null, null, null), new byte[] {1})),
                new KafkaClientsSender(guard, null, true), guard, platform, CircuitBreakerRegistry.ofDefaults(), meters,
                ObservationRegistry.NOOP, Duration.ofSeconds(10));

        assertThatThrownBy(() -> call.call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), false)))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));

        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers))) {
            assertThat(admin.listOffsets(Map.of(new TopicPartition(topic, 0),
                    org.apache.kafka.clients.admin.OffsetSpec.latest())).all().get().values())
                    .allSatisfy(info -> assertThat(info.offset()).isZero());
        }
    }

    @Test
    void aTargetClusterThatIsDownIsTransient() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        String down = "127.0.0.1:" + closedPort;

        assertThatThrownBy(() -> call(down, "orders-out", new byte[] {1}, Duration.ofSeconds(3))
                .call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false)))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.TRANSIENT));
    }
}
