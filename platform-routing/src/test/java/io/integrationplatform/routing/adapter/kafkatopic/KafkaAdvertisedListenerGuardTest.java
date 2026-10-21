package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E16 Karan F7 (CWE-918), no broker: the bootstrap hosts are guarded, but a broker answers the metadata request with
 * ITS advertised listeners and the producer dials those. Every advertised broker of the topic - leader and replicas -
 * goes through the same destination guard BEFORE the record is handed to the producer, so an advertised internal
 * address (a link-local metadata service, a private network, a platform host, a name off the allow-list) is refused
 * and nothing is sent. Bootstrap addresses are pinned only for clear-text protocols; TLS keeps the name so the
 * certificate's hostname check applies to every hop.
 */
class KafkaAdvertisedListenerGuardTest {

    private static final Map<String, String> DNS = Map.of(
            "kafka.partner.example", "93.184.216.34",
            "b1.partner.example", "93.184.216.35",
            "b2.partner.example", "93.184.216.36",
            "meta.partner.example", "169.254.169.254",
            "internal.partner.example", "10.20.30.40",
            "loop.partner.example", "127.0.0.1",
            "platform-db.partner.example", "93.184.216.99");

    private static final DestinationGuard GUARD = new DestinationGuard(AllowList.of(List.of("*.partner.example")),
            DNS_RESOLVER(), List.of(), List.of(), List.of("93.184.216.99"));

    private static org.apache.kafka.common.Cluster cluster(String topic, Node leader, Node... replicas) {
        List<Node> all = new ArrayList<>(List.of(replicas));
        all.add(leader);
        PartitionInfo partition = new PartitionInfo(topic, 0, leader, all.toArray(new Node[0]),
                all.toArray(new Node[0]));
        return new Cluster("test", all, List.of(partition), Set.of(), Set.of());
    }

    private static io.integrationplatform.config.destination.HostResolver DNS_RESOLVER() {
        return host -> {
            String address = DNS.get(host);
            if (address == null) {
                throw new UnknownHostException(host);
            }
            return List.of(InetAddress.getByName(address));
        };
    }

    private static final Partitioner FIRST = new Partitioner() {
        @Override
        public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
                Cluster cluster) {
            return 0;
        }

        @Override
        public void close() {
        }

        @Override
        public void configure(Map<String, ?> configs) {
        }
    };

    private static MockProducer<byte[], byte[]> producerAdvertising(Cluster cluster) {
        return new MockProducer<>(cluster, true, FIRST, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static KafkaTopicSettings settings(String protocol) {
        return KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"kafka.partner.example:9092\",\"topic\":\"orders\","
                + "\"securityProtocol\":\"" + protocol + "\"}");
    }

    private static KafkaClientsSender sender(MockProducer<byte[], byte[]> producer, List<Properties> built) {
        Function<Properties, Producer<byte[], byte[]>> factory = props -> {
            built.add(props);
            return producer;
        };
        return new KafkaClientsSender(GUARD, null, factory);
    }

    private static void send(KafkaClientsSender sender, KafkaTopicSettings settings) throws Exception {
        sender.send(settings, null, UUID.randomUUID(), new byte[] {1}, Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"meta.partner.example", "internal.partner.example", "loop.partner.example",
        "platform-db.partner.example"})
    @Tag("security")
    void anAdvertisedLeaderThatResolvesToARefusedAddressIsRefusedAndNothingIsSent(String advertised) {
        MockProducer<byte[], byte[]> producer = producerAdvertising(
                cluster("orders", new Node(1, advertised, 9092)));

        assertThatThrownBy(() -> send(sender(producer, new ArrayList<>()), settings("PLAINTEXT")))
                .isInstanceOf(DestinationRefusedException.class);

        assertThat(producer.history()).isEmpty();
        assertThat(producer.closed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "169.254.169.254", "[::1]", "127.0.0.1"})
    @Tag("security")
    void anAdvertisedAddressLiteralInARefusedRangeIsRefused(String advertised) {
        MockProducer<byte[], byte[]> producer = producerAdvertising(
                cluster("orders", new Node(1, advertised.replaceAll("[\\[\\]]", ""), 9092)));

        assertThatThrownBy(() -> send(sender(producer, new ArrayList<>()), settings("SSL")))
                .isInstanceOf(DestinationRefusedException.class);

        assertThat(producer.history()).isEmpty();
    }

    @Test
    @Tag("security")
    void anAdvertisedNameOffTheAllowListIsRefused() {
        MockProducer<byte[], byte[]> producer = producerAdvertising(
                cluster("orders", new Node(1, "broker.attacker.example", 9092)));

        assertThatThrownBy(() -> send(sender(producer, new ArrayList<>()), settings("SSL")))
                .isInstanceOf(DestinationRefusedException.class);
        assertThat(producer.history()).isEmpty();
    }

    @Test
    @Tag("security")
    void aReplicaThatIsNotTheLeaderIsGuardedToo() {
        MockProducer<byte[], byte[]> producer = producerAdvertising(cluster("orders",
                new Node(1, "b1.partner.example", 9092), new Node(2, "internal.partner.example", 9092)));

        assertThatThrownBy(() -> send(sender(producer, new ArrayList<>()), settings("SSL")))
                .isInstanceOf(DestinationRefusedException.class);
        assertThat(producer.history()).isEmpty();
    }

    @Test
    void publicAdvertisedBrokersOnTheAllowListAreSent() throws Exception {
        MockProducer<byte[], byte[]> producer = producerAdvertising(cluster("orders",
                new Node(1, "b1.partner.example", 9092), new Node(2, "b2.partner.example", 9092)));

        send(sender(producer, new ArrayList<>()), settings("SSL"));

        assertThat(producer.history()).hasSize(1);
        assertThat(producer.closed()).isTrue();
    }

    @Test
    @Tag("security")
    void aRefusedAdvertisedBrokerIsAPermanentFaultAtTheTargetCall() {
        MockProducer<byte[], byte[]> producer = producerAdvertising(
                cluster("orders", new Node(1, "meta.partner.example", 9092)));
        KafkaTopicSettings settings = settings("SSL");
        KafkaTopicTargetCall call = new KafkaTopicTargetCall(
                send -> Optional.of(new KafkaMessage(settings, new byte[] {1})), sender(producer, new ArrayList<>()),
                GUARD, Set.of("platform-work"), CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, Duration.ofSeconds(5));

        assertThatThrownBy(() -> call.call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), false)))
                .isInstanceOfSatisfying(TargetFaultException.class, fault -> {
                    assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
                    assertThat(fault.getMessage()).doesNotContain("169.254.169.254");
                });
        assertThat(producer.history()).isEmpty();
    }

    @Test
    @Tag("security")
    void clearTextBootstrapIsPinnedToTheCheckedAddress() throws Exception {
        List<Properties> built = new ArrayList<>();
        MockProducer<byte[], byte[]> producer = producerAdvertising(cluster("orders",
                new Node(1, "b1.partner.example", 9092)));

        send(sender(producer, built), settings("PLAINTEXT"));

        assertThat(built).singleElement().satisfies(
                props -> assertThat(props.getProperty("bootstrap.servers")).isEqualTo("93.184.216.34:9092"));
    }

    @Test
    @Tag("security")
    void tlsBootstrapKeepsTheNameSoTheCertificateHostnameCheckApplies() throws Exception {
        List<Properties> built = new ArrayList<>();
        MockProducer<byte[], byte[]> producer = producerAdvertising(cluster("orders",
                new Node(1, "b1.partner.example", 9092)));

        send(sender(producer, built), settings("SSL"));

        assertThat(built).singleElement().satisfies(props -> {
            assertThat(props.getProperty("bootstrap.servers")).isEqualTo("kafka.partner.example:9092");
            assertThat(props.getProperty("ssl.endpoint.identification.algorithm")).isEqualTo("https");
        });
    }

    @Test
    @Tag("security")
    void aRefusedBootstrapNeverBuildsAProducer() {
        List<Properties> built = new ArrayList<>();
        KafkaTopicSettings internal = KafkaTopicSettings.fromJson(
                "{\"bootstrapServers\":\"internal.partner.example:9092\",\"topic\":\"orders\"}");

        assertThatThrownBy(() -> send(sender(producerAdvertising(cluster("orders", new Node(1, "b1.partner.example",
                9092))), built), internal)).isInstanceOf(DestinationRefusedException.class);
        assertThat(built).isEmpty();
    }
}
