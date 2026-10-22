package io.integrationplatform.routing.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.topics.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-044 acceptance, E8-F1/E8-F2 "Containers: Kafka" - the harness the whole routing lane A/B/C wave reuses is a
 * real broker (E8-C8) with the properties the routing stories need, each asserted against the running broker:
 * <ul>
 * <li>a single KRaft broker with topic auto-creation OFF,</li>
 * <li>exactly the three product topics from ADR-010 and no others (the harness creates them, nothing else
 * does),</li>
 * <li>a produce/consume round trip that works after any previous test used the broker (reusability).</li>
 * </ul>
 * The reusability round trip is also the data-path proof of the E8-F2-S1 {@code @negative} scenario "The
 * product never depends on Kafbat": the platform's Kafka path runs with no Kafbat container anywhere.
 */
@Tag("container")
class KafkaHarnessTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    @Test
    void theHarnessIsOneBrokerWithTopicAutoCreateOff() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaHarness.get().bootstrapServers()))) {
            var nodes = admin.describeCluster().nodes().get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(nodes).as("a single broker per the harness definition").hasSize(1);

            String brokerId = String.valueOf(nodes.iterator().next().id());
            var resource = new ConfigResource(ConfigResource.Type.BROKER, brokerId);
            var config = admin.describeConfigs(List.of(resource)).all().get(TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS).get(resource);
            assertThat(config.get("auto.create.topics.enable").value())
                    .as("topic auto-create is off - the harness and only the harness decides the topics")
                    .isEqualTo("false");
        }
    }

    @Test
    void theHarnessCreatesExactlyTheThreeProductTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaHarness.get().bootstrapServers()))) {
            var names = admin.listTopics().names().get(TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            // other classes create orders-out-* / platform-guard-* topics on the shared singleton broker: only the
            // platform.* product topics are in scope here
            var productTopics = names.stream().filter(n -> n.startsWith("platform.")).toList();
            assertThat(productTopics).as("exactly the ADR-010 product topics exist and nothing else")
                    .containsExactlyInAnyOrder(Topics.MESSAGE_ACCEPTED, Topics.DELIVERY_DUE, Topics.POISON);
        }
    }

    @Test
    void theHarnessIsReusableRoundTripWithNoKafbat() throws Exception {
        KafkaHarness harness = KafkaHarness.get();
        assertThat(KafkaHarness.get()).as("the same broker for every test in the JVM").isSameAs(harness);

        byte[] key = "e8-harness-key".getBytes();
        byte[] value = "e8-harness-value".getBytes();
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, harness.bootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000);
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, harness.bootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "e8-harness-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps);
                KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps)) {
            producer.send(new ProducerRecord<>(Topics.MESSAGE_ACCEPTED, key, value)).get(30, java.util.concurrent.TimeUnit.SECONDS);

            consumer.subscribe(List.of(Topics.MESSAGE_ACCEPTED));
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(5));
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                records = consumer.poll(Duration.ofSeconds(5));
            }
            assertThat(records.count()).as("the work topic carries records end to end").isPositive();
            assertThat(records.records(Topics.MESSAGE_ACCEPTED))
                    .as("the produced record comes back with its key and value")
                    .anySatisfy(record -> {
                        assertThat(record.key()).isEqualTo(key);
                        assertThat(record.value()).isEqualTo(value);
                    });
        }
    }
}