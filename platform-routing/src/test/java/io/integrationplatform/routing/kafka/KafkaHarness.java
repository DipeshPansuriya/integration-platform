package io.integrationplatform.routing.kafka;

import io.integrationplatform.routing.topics.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The Kafka Testcontainers harness every routing test reuses (T-044, E8-F1): one KRaft broker on
 * {@code apache/kafka:4.3.1} matching the pinned kafka-clients 4.3.1 (R05, R12), topic auto-creation OFF so
 * nothing a test forgets to create can hide a subscription bug, exactly the three {@link
 * io.integrationplatform.routing.topics.Topics} topics created once per JVM, and one broker instance shared by
 * {@link #get()}. Singleton style follows platform-delivery's DeliveryTestDatabase. Runs under the {@code
 * container} tag only (R03/R06), one Docker suite at a time.
 */
public final class KafkaHarness {

    /** Partitions per work topic - enough for same-key ordering and multi-partition consumer tests. */
    public static final int PARTITION_COUNT = 3;

    private static KafkaHarness instance;
    private static RuntimeException setupFailure;

    private final Network network = Network.newNetwork();

    private final KafkaContainer container = new KafkaContainer(KafkaImages.KAFKA)
            // An in-network listener advertised as "kafka:9095": fixtures joined to the same network (the Kafbat
            // container resolves it via the network alias) reach the broker without the host mapping. Its own
            // port - Kafka 4.x rejects two listeners on one port.
            .withListener("kafka:9095")
            // The harness decides the topics (T-044): a test that forgets to create a topic must fail, never
            // silently ride an auto-created one that hides a wrong subscription.
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withNetwork(network)
            // The advertised listener is "kafka:9095" - on this network the name "kafka" must be the broker's
            // DNS alias, or an in-network fixture (the Kafbat container) cannot resolve it.
            .withNetworkAliases("kafka");

    private KafkaHarness() {
    }

    public static synchronized KafkaHarness get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            KafkaHarness harness = new KafkaHarness();
            try {
                harness.container.start();
                harness.createTheThreeTopics();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("Kafka harness setup failed: " + e.getMessage(), e);
                throw setupFailure;
            }
            instance = harness;
        }
        return instance;
    }

    /**
     * Creates exactly the three ADR-010 topics the stage consumers subscribe to and the poison producer writes
     * ({@link io.integrationplatform.routing.topics.Topics}): nothing else exists, auto-creation is off.
     */
    private void createTheThreeTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.MESSAGE_ACCEPTED, PARTITION_COUNT, (short) 1),
                    new NewTopic(Topics.DELIVERY_DUE, PARTITION_COUNT, (short) 1),
                    new NewTopic(Topics.POISON, PARTITION_COUNT, (short) 1)))
                    .all().get(1, TimeUnit.MINUTES);
            // The topics are visible before the first test's client connects (Kafka metadata is not instant).
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            Set<String> names = admin.listTopics().names().get(1, TimeUnit.MINUTES);
            while (names.size() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
                names = admin.listTopics().names().get(1, TimeUnit.MINUTES);
            }
        }
    }

    /** The bootstrap server a host-side test client connects to. */
    public String bootstrapServers() {
        return container.getBootstrapServers();
    }

    /** The network the broker listens on - a container fixture joins it to reach Kafka inside Docker. */
    public Network network() {
        return network;
    }
}