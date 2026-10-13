package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.kafka.KafkaHarness;
import io.integrationplatform.routing.poison.PoisonTopic;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The protected path of the stage consumers against the real broker and the migrated database (T-111, T-113,
 * T-181; E8-C4/E8-C6/E8-C7): the row decides the tenant through {@code ipaas_work_tenant} - a forged tenant
 * header or value is ignored; a record for a missing row does nothing; an unreadable record goes to the poison
 * topic exactly once and the partition moves on; a transient database error pauses the partition, retries the
 * same record after a back-off and never commits the offset before the work succeeded - no record skipped, none
 * poisoned, and no retry topic exists anywhere (E8-C6, E8-C7).
 */
@Tag("container")
class StageProtectedPathContainerTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01";

    private final StageFixture db = StageFixture.get();

    @Test
    void theRowDecidesTheTenantAndAForgedOneIsIgnored() throws Exception {
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);
        try (Scenario scenario = new Scenario()) {
            scenario.produce(rowOfTenantA, Map.of("tenant_id", StageFixture.TENANT_B + "", "payload", "forged"));

            scenario.stage.runUntil(Instant.now().plusSeconds(15));

            assertThat(scenario.handledTenants)
                    .as("the work ran under tenant A - the row's tenant, never the forged header")
                    .containsExactly(StageFixture.TENANT_A);
            assertThat(scenario.handledTraceparents)
                    .as("the traceparent header carried through - the only header the contract reads")
                    .containsExactly(TRACEPARENT);
            assertThat(scenario.committedOffset()).isEqualTo(1L);
            byte[] ownRecord = recordJson(rowOfTenantA).getBytes();
            assertThat(scenario.poisonValues())
                    .as("a readable record is never poisoned")
                    .noneMatch(value -> java.util.Arrays.equals(ownRecord, value));
        }
    }

    @Test
    void aRecordForAMissingRowDoesNothing() throws Exception {
        try (Scenario scenario = new Scenario()) {
            UUID idOfNoRow = UUID.fromString("7e7e7e7e-0000-4000-8000-00000000000e");
            scenario.produceRaw(idOfNoRow.toString().getBytes(), recordJson(idOfNoRow).getBytes(), Map.of());

            scenario.stage.runUntil(Instant.now().plusSeconds(15));

            assertThat(scenario.handledTenants)
                    .as("nothing is processed for a missing row").isEmpty();
            assertThat(scenario.committedOffset())
                    .as("the empty outcome is acknowledged - the partition moves on").isEqualTo(1L);
            byte[] ownRecord = recordJson(UUID.fromString("7e7e7e7e-0000-4000-8000-00000000000e")).getBytes();
            assertThat(scenario.poisonValues())
                    .as("a missing row is not unreadable - its bytes never reach poison")
                    .noneMatch(value -> java.util.Arrays.equals(ownRecord, value));
        }
    }

    @Test
    void anUnreadableRecordGoesToPoisonOnceAndThePartitionMovesOn() throws Exception {
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);
        try (Scenario scenario = new Scenario()) {
            byte[] garbage = "not the record contract at all".getBytes();
            scenario.produceRaw("garbage-key".getBytes(), garbage, Map.of());
            scenario.produce(rowOfTenantA, Map.of());

            scenario.stage.runUntil(Instant.now().plusSeconds(15));

            List<byte[]> poisonOfTheGarbage = scenario.poisonValues().stream()
                    .filter(value -> java.util.Arrays.equals(garbage, value)).toList();
            assertThat(poisonOfTheGarbage)
                    .as("exactly one poison write of the raw bytes - never a retry path")
                    .hasSize(1);
            assertThat(scenario.handledTenants)
                    .as("the partition moved on to the next record")
                    .containsExactly(StageFixture.TENANT_A);
            assertThat(scenario.committedOffset()).as("both offsets committed in order").isEqualTo(2L);
        }
    }

    @Test
    void aTransientDatabaseErrorPausesRetriesTheRecordAndNeverSkipsIt() throws Exception {
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);
        try (Scenario scenario = new Scenario()) {
            scenario.rows.failTheFirstVisibleChecks(2);
            scenario.produce(rowOfTenantA, Map.of());

            scenario.stage.runUntil(Instant.now().plusSeconds(30));

            assertThat(scenario.handledTenants)
                    .as("the record was handled exactly once, after the retry - none skipped").hasSize(1)
                    .containsExactly(StageFixture.TENANT_A);
            assertThat(scenario.committedOffset())
                    .as("the offset is committed only after the work succeeded").isEqualTo(1L);
            assertThat(scenario.consumer.paused())
                    .as("the partition was resumed when the retry went through").isEmpty();
            byte[] ownRecord = recordJson(rowOfTenantA).getBytes();
            assertThat(scenario.poisonValues())
                    .as("a transient database error never goes to poison")
                    .noneMatch(value -> java.util.Arrays.equals(ownRecord, value));
        }
    }

    @Test
    void noRetryTopicExistsAnywhere() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers()))) {
            Set<String> topics = admin.listTopics().names().get(1, TimeUnit.MINUTES);
            assertThat(topics).as("ADR-010: the three work topics exist - never a retry topic")
                    .contains(io.integrationplatform.routing.topics.Topics.MESSAGE_ACCEPTED,
                            io.integrationplatform.routing.topics.Topics.DELIVERY_DUE,
                            io.integrationplatform.routing.topics.Topics.POISON)
                    .as("no retry or delayed-retry topic exists anywhere (E8-C6, E8-C7)")
                    .allMatch(name -> !(name.toLowerCase().contains("retry") || name.toLowerCase().contains("delay")));
        }
    }

    /**
     * One test's wiring: real broker, the real protected rows of the fixture, a real poison producer. The work
     * topic is a fresh one-topic-per-scenario broker topic (one partition) - the shared ADR-010 work topics
     * carry earlier scenarios' records, and the scenarios must not consume each other's work.
     */
    private final class Scenario implements AutoCloseable {

        final String topic = "lane-a-stage-" + UUID.randomUUID();
        final TopicPartition work = new TopicPartition(topic, 0);
        final Producer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        final FailingRows rows = new FailingRows();
        final List<UUID> handledTenants = new ArrayList<>();
        final List<String> handledTraceparents = new ArrayList<>();
        final Consumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "stage-container-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        final StageConsumer stage;

        Scenario() {
            this.stage = new StageConsumer(consumer, new WorkRecordParser(), rows,
                    (record, tenantId) -> {
                        handledTenants.add(tenantId);
                        handledTraceparents.add(new String(record.traceparent()));
                    },
                    new PoisonTopic(producer),
                    attempt -> Duration.ofMillis(200L * attempt),
                    Duration.ofMillis(100), Clock.systemUTC(), Thread::sleep);
            try (AdminClient admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers()))) {
                admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1)))
                        .all().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("the scenario topic could not be created: " + e.getMessage(), e);
            }
            consumer.assign(List.of(work));
            consumer.seekToBeginning(List.of(work));
        }

        /** Produces an ids-only record with its key, adding {@code forgedHeaders} for the forged-tenant case. */
        void produce(UUID rowId, Map<String, String> forgedHeaders) {
            produceRaw(("work-" + rowId).getBytes(), recordJson(rowId).getBytes(), forgedHeaders);
        }

        private void produceRaw(byte[] key, byte[] value, Map<String, String> headers) {
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, 0, key, value);
            record.headers().add("traceparent", TRACEPARENT.getBytes());
            headers.forEach((name, headerValue) -> record.headers().add(name, headerValue.getBytes()));
            try {
                producer.send(record).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("the test producer failed: " + e.getMessage(), e);
            }
        }

        /** The committed offset of the work partition, read back from the broker. */
        Long committedOffset() {
            org.apache.kafka.clients.consumer.OffsetAndMetadata committed =
                    consumer.committed(Set.of(work)).get(work);
            return committed == null ? null : committed.offset();
        }

        /**
         * Every record on the poison topic (all partitions, from the beginning): polled until collected at
         * least what the partitions' end offsets say - the first idle poll can precede the fetch positions.
         */
        List<byte[]> poisonValues() {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int p = 0; p < KafkaHarness.PARTITION_COUNT; p++) {
                partitions.add(new TopicPartition(io.integrationplatform.routing.topics.Topics.POISON, p));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<byte[]> values = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(5);
            long target = 0;
            for (TopicPartition partition : consumer.endOffsets(partitions).keySet()) {
                target += consumer.endOffsets(partitions).get(partition);
            }
            while (values.size() < target && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    values.add(record.value());
                }
            }
            consumer.assign(List.of(work));
            consumer.seekToBeginning(List.of(work));
            return values;
        }

        @Override
        public void close() {
            consumer.close(Duration.ofSeconds(2));
            producer.close(Duration.ofSeconds(2));
        }
    }

    /** The real protected rows, with the first n visibility checks failing like a transient database blip. */
    private final class FailingRows implements StageConsumer.ProtectedRows {

        private final StageConsumer.ProtectedRows real = db.protectedRows();
        private int visibleFailuresLeft;

        void failTheFirstVisibleChecks(int times) {
            visibleFailuresLeft = times;
        }

        @Override
        public UUID tenantFor(WorkKind kind, UUID id) {
            return real.tenantFor(kind, id);
        }

        @Override
        public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
            if (visibleFailuresLeft > 0) {
                visibleFailuresLeft--;
                throw new TransientWorkException("the database call failed transiently");
            }
            return real.visibleTo(tenantId, kind, id);
        }
    }

    private static String recordJson(UUID rowId) {
        return "{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + rowId + "\"}";
    }
}