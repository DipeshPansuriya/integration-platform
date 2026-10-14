package io.integrationplatform.routing.e8.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.internal.relay.JdbcOutboxAccess;
import io.integrationplatform.ingest.internal.relay.PostgresOutboxRelay;
import io.integrationplatform.ingest.internal.relay.RelayAlerts;
import io.integrationplatform.ingest.internal.relay.RelaySettings;
import io.integrationplatform.ingest.internal.relay.RelayTenantContext;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.routing.e8.duplicate.E8DuplicateStack;
import io.integrationplatform.routing.kafka.KafkaImages;
import io.integrationplatform.routing.publish.KafkaPublishPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;

/**
 * T-191, E8-F1-S3 "Kafka down, rows wait" against a real broker and the real outbox: Kafka stops answering
 * during a run (the broker container is paused - its port stays, so the relay reconnects to the same address);
 * intake keeps writing rows; the rows stay in the outbox, the relay's one Kafka breaker opens, the outbox-age
 * alert fires; after Kafka returns every row is published in order.
 *
 * <p>The spec's 3-minute outage and 60 s alert are configuration: the test passes a 3 s alert, a 2-failure / 2 s
 * breaker and a producer with test-sized timeouts so the scenario runs in seconds. The broker here is this test's
 * own (pausing the shared KafkaHarness would break every other suite in the JVM); the database is the E8 lane C1
 * fixture ({@link E8DuplicateStack}, read only - its tenant A and key bucket).
 *
 * <p>Runs only with {@code -Pcontainer} under the one-suite-at-a-time Docker lock (R03, R06).
 */
@Tag("container")
class E8KafkaDownRowsWaitContainerTest {

    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaImages.KAFKA);

    @BeforeAll
    static void startTheOwnBroker() throws Exception {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(OutboxTopic.MESSAGE_ACCEPTED.topicName(), 1, (short) 1)))
                    .all().get(1, TimeUnit.MINUTES);
        }
    }

    @AfterAll
    static void stopTheOwnBroker() {
        KAFKA.stop();
    }

    @Test
    @DisplayName("Kafka down, rows wait")
    void kafkaDownRowsWait() throws Exception {
        E8DuplicateStack stack = E8DuplicateStack.get();
        RelayTenantContext tenants = new RelayTenantContext();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<Duration> ageAlerts = new CopyOnWriteArrayList<>();
        RelaySettings fast = new RelaySettings(2, Duration.ofSeconds(2), Duration.ofMillis(200),
                Duration.ofSeconds(1), 0.0, Duration.ofSeconds(3));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(testSizedProducer())) {
            PostgresOutboxRelay relay = new PostgresOutboxRelay(() -> List.of(E8DuplicateStack.TENANT_A),
                    new JdbcOutboxAccess(stack.appJdbcClient(), tenants), tenants,
                    stack.stampedTransactions(tenants::currentTenantId), new KafkaPublishPort(producer),
                    Duration.ofSeconds(4), 50, fast, meters, ObservationRegistry.NOOP, Clock.systemUTC(),
                    (age, rows) -> ageAlerts.add(age));

            // given: Kafka is up and the relay has published before (its producer knows the cluster)
            long warmUp = stack.insertOutboxRow(E8DuplicateStack.TENANT_A, OutboxTopic.MESSAGE_ACCEPTED,
                    UUID.randomUUID(), "warm-up", null);
            runUntil(relay, () -> !unpublished(stack, warmUp), Duration.ofSeconds(60));

            // when: Kafka stops answering and intake continues
            pauseKafka();
            List<UUID> accepted = new ArrayList<>();
            List<Long> rowIds = new ArrayList<>();
            try {
                for (int row = 0; row < 5; row++) {
                    UUID aggregate = UUID.randomUUID();
                    accepted.add(aggregate);
                    rowIds.add(stack.insertOutboxRow(E8DuplicateStack.TENANT_A, OutboxTopic.MESSAGE_ACCEPTED,
                            aggregate, "kafka-down-key", null));
                }
                boolean[] breakerSeenOpen = {false};
                runUntil(relay, () -> {
                    breakerSeenOpen[0] |= kafkaBreakerOpen(meters);
                    return breakerSeenOpen[0] && !ageAlerts.isEmpty();
                }, Duration.ofSeconds(90));

                // then: the rows wait, the breaker is open, the alert fired
                assertThat(breakerSeenOpen[0]).as("the relay's one Kafka breaker opened").isTrue();
                assertThat(ageAlerts).as("the outbox-age alert fired after the (test-sized) threshold")
                        .isNotEmpty()
                        .allSatisfy(age -> assertThat(age).isGreaterThanOrEqualTo(Duration.ofSeconds(3)));
                for (long rowId : rowIds) {
                    assertThat(unpublished(stack, rowId)).as("row %d stays in the outbox while Kafka is down", rowId)
                            .isTrue();
                }
                assertThat(meters.get("platform.outbox.unpublished.rows").gauge().value())
                        .as("the backlog gauge counts the waiting rows").isGreaterThanOrEqualTo(5.0);
            } finally {
                unpauseKafka();
            }

            // and: after Kafka returns every row is published, in order
            runUntil(relay, () -> rowIds.stream().noneMatch(rowId -> unpublished(stack, rowId)),
                    Duration.ofSeconds(90));
            assertThat(firstArrivalOrder(accepted)).as("every row published after the return, in outbox order")
                    .containsExactlyElementsOf(accepted);
        }
    }

    private static void runUntil(PostgresOutboxRelay relay, java.util.function.BooleanSupplier done,
            Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached within %s", limit).isLessThan(deadline);
            relay.runOnce();
            Thread.sleep(200);
        }
    }

    private static boolean unpublished(E8DuplicateStack stack, long rowId) {
        try {
            return stack.outboxRowUnpublished(rowId);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean kafkaBreakerOpen(SimpleMeterRegistry meters) {
        Gauge open = meters.find("resilience4j.circuitbreaker.state").tags("name", "kafka", "state", "open")
                .gauge();
        return open != null && open.value() == 1.0;
    }

    /** The accepted aggregates in the order their first copy reached the topic (a resend may add a later copy). */
    private static List<UUID> firstArrivalOrder(List<UUID> accepted) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-down-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        Set<UUID> seen = new LinkedHashSet<>();
        try (KafkaConsumer<byte[], byte[]> reader = new KafkaConsumer<>(config)) {
            reader.subscribe(List.of(OutboxTopic.MESSAGE_ACCEPTED.topicName()));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!seen.containsAll(accepted) && System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : reader.poll(Duration.ofMillis(500))) {
                    String value = new String(record.value(), StandardCharsets.UTF_8);
                    for (UUID aggregate : accepted) {
                        if (value.contains(aggregate.toString())) {
                            seen.add(aggregate);
                        }
                    }
                }
            }
        }
        return List.copyOf(seen);
    }

    private static Map<String, Object> testSizedProducer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // the production 10 s / 30 s / 10 s (KafkaPublishPortConfiguration), test-sized
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_500);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        return config;
    }

    private static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
}
