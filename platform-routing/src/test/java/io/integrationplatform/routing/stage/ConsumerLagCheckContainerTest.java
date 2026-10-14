package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.kafka.KafkaHarness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-191, E8-F1-S3 "Consumer lag alert" against the real broker (KafkaHarness, apache/kafka 4.3.1): a consumer
 * group more than 1,000 records - or more than the age threshold - behind makes the check fire the lag alert
 * naming the group and its partitions; a group within both thresholds raises nothing. The check reads the
 * group's committed offsets and the log end and time offsets through the admin client, so it works even when
 * the consumer itself is stuck or gone. The 2-minute default is configured down to 1 s here so the test runs
 * fast; the default stays in the alert rules and {@code StageConsumer} wiring.
 *
 * <p>Runs only with {@code -Pcontainer} under the one-suite-at-a-time Docker lock (R03, R06).
 */
@Tag("container")
class ConsumerLagCheckContainerTest {

    private static Admin admin;

    @BeforeAll
    static void connect() {
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaHarness.get().bootstrapServers()));
    }

    @AfterAll
    static void close() {
        admin.close();
    }

    @Test
    void aGroupMoreThanOneThousandRecordsBehindFiresTheLagAlertWithTheGroupAndPartitions() throws Exception {
        String topic = newTopic(2);
        String group = "lag-group-" + UUID.randomUUID();
        produce(topic, 0, 1_001);
        commit(group, topic, 0, 0);
        commit(group, topic, 1, 0);
        List<LagAlert> raised = new ArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        Optional<LagAlert> alert = check(group, topic, Duration.ofMinutes(2), meters, raised).check();

        assertThat(alert).as("1,001 records behind is above the 1,000 threshold").isPresent();
        assertThat(alert.get().group()).isEqualTo(group);
        assertThat(alert.get().partitions()).as("the alert names the lagging partition")
                .containsExactly(new TopicPartition(topic, 0));
        assertThat(alert.get().totalLag()).isEqualTo(1_001);
        assertThat(raised).as("the alert is raised, not only returned").hasSize(1);
        assertThat(meters.get("platform.stage.lag.records").tags("group", group, "topic", topic, "partition", "0")
                .gauge().value()).as("lag per group and partition").isEqualTo(1_001.0);
        assertThat(meters.get("platform.alerts.fired").tags("alert", "consumer_lag").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aGroupWhoseOldestUnconsumedRecordIsOlderThanTheThresholdFiresTheLagAlert() throws Exception {
        String topic = newTopic(2);
        String group = "lag-group-" + UUID.randomUUID();
        produce(topic, 1, 3);
        commit(group, topic, 0, 0);
        commit(group, topic, 1, 0);
        Thread.sleep(1_500); // the records age past the (test-sized) 1 s threshold

        Optional<LagAlert> alert = check(group, topic, Duration.ofSeconds(1), new SimpleMeterRegistry(),
                new ArrayList<>()).check();

        assertThat(alert).as("3 records is far below 1,000, but the oldest is older than the age threshold")
                .isPresent();
        assertThat(alert.get().group()).isEqualTo(group);
        assertThat(alert.get().stalePartitions()).containsExactly(new TopicPartition(topic, 1));
        assertThat(alert.get().partitions()).containsExactly(new TopicPartition(topic, 1));
    }

    @Test
    void aGroupWithinBothThresholdsRaisesNothing() throws Exception {
        String topic = newTopic(1);
        String group = "lag-group-" + UUID.randomUUID();
        produce(topic, 0, 10);
        commit(group, topic, 0, 5);
        List<LagAlert> raised = new ArrayList<>();

        Optional<LagAlert> alert = check(group, topic, Duration.ofMinutes(2), new SimpleMeterRegistry(), raised)
                .check();

        assertThat(alert).as("5 records behind, all fresh").isEmpty();
        assertThat(raised).isEmpty();
    }

    private static ConsumerLagCheck check(String group, String topic, Duration maxAge, SimpleMeterRegistry meters,
            List<LagAlert> raised) {
        return new ConsumerLagCheck(admin, group, List.of(topic), 1_000, maxAge, Duration.ofSeconds(10),
                Clock.systemUTC(), meters, new StageAlerts() {
                    @Override
                    public void poisonArrived(String g, String t, int partition, long offset, String reason) {
                    }

                    @Override
                    public void lagExceeded(LagAlert alert) {
                        raised.add(alert);
                    }
                });
    }

    private static String newTopic(int partitions) throws Exception {
        String topic = "lag-check-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(1, TimeUnit.MINUTES);
        return topic;
    }

    private static void produce(String topic, int partition, int count) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaHarness.get().bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(topic, partition, "k", "v" + i));
            }
            producer.flush();
        }
    }

    private static void commit(String group, String topic, int partition, long offset) throws Exception {
        admin.alterConsumerGroupOffsets(group,
                Map.of(new TopicPartition(topic, partition), new OffsetAndMetadata(offset)))
                .all().get(1, TimeUnit.MINUTES);
    }
}
