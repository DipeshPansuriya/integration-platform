package io.integrationplatform.routing.e8.duplicate;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.internal.dedup.JdbcDuplicateGuard;
import io.integrationplatform.ingest.internal.relay.PostgresOutboxRelay;
import io.integrationplatform.ingest.internal.relay.RelayTenantContext;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.routing.kafka.KafkaHarness;
import io.integrationplatform.routing.kafka.KafkaImages;
import io.integrationplatform.routing.topics.Topics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-114 (E8-F1-S2, traces E8-C9 + E8-C8): the post-crash duplicate driven against a REAL Kafka (Testcontainers)
 * over the real chain - the real outbox row in the real PostgreSQL, the real {@link PostgresOutboxRelay} claim /
 * publish / mark cycle, and the real {@link JdbcDuplicateGuard} the consumer asks. The record consumer itself and
 * the product's Kafka publish adapter are wave 1's lanes (lane A T-111/T-181, lane B T-110); while they are not
 * integrated this suite is the RED deliverable: it ends on a seam check that names the missing piece, and its own
 * chain (broker, database, relay, guard) is proven before that check runs - the red is missing adapter/consumer,
 * never missing test setup. arjun-fullstack re-runs this after the wave 1 integration and the suite turns green;
 * the stand-in port in {@link E8TestKafkaPublishPort} is then swappable for lane B's adapter.
 */
@Tag("container")
class E8PostCrashDuplicateRealKafkaTest {

    @Test
    @DisplayName("A post-crash duplicate on real Kafka is caught")
    void aPostCrashDuplicateOnRealKafkaIsCaught() throws Exception {
        // given: the two real containers, and one accepted message as an outbox row of tenant A
        E8DuplicateStack stack = E8DuplicateStack.get();
        KafkaHarness kafka = KafkaHarness.get();

        UUID aggregateId = UUID.randomUUID();
        // E8-C2's key shape: SHA-256 of (tenant, interface, business key); the interface/business pair is this
        // row's arbitrary identity - the row's record_key is what the relay publishes with, whatever its provenance.
        String recordKey = "0000000a-" + sha256(E8DuplicateStack.TENANT_A + "·test-interface·business-key")
                .substring(0, 8);
        String traceparent = "00-" + uuidHex(32) + "-" + uuidHex(16) + "-01";
        long rowId = stack.insertOutboxRow(E8DuplicateStack.TENANT_A, OutboxTopic.MESSAGE_ACCEPTED, aggregateId,
                recordKey, traceparent);
        String idempotencyKey = "outbox:" + rowId;

        // the relay: real claim/mark over the real database, the tenant loop the ActiveTenantSource supplies
        RelayTenantContext tenants = new RelayTenantContext();
        E8TestOutboxAccess access = new E8TestOutboxAccess(stack.appJdbcClient(), tenants);
        PostgresOutboxRelay relay = new PostgresOutboxRelay(() -> List.of(E8DuplicateStack.TENANT_A), access,
                tenants, stack.stampedTransactions(tenants::currentTenantId),
                new E8TestKafkaPublishPort(kafka.bootstrapServers()), Duration.ofSeconds(10), 8);

        // when: cycle 1 - the relay publishes, the broker acknowledges, and the relay "dies" before the row mark
        access.markAfterAck(false);
        assertThat(relay.runOnce()).as("the crash leaves the cycle with nothing marked").isZero();
        assertThat(stack.outboxRowUnpublished(rowId))
                .as("the crash window: record 1 is on the broker yet the row is unmarked (at least once)")
                .isTrue();

        // when: the same record arrives twice - cycle 2 re-claims and re-publishes the unmarked row
        access.markAfterAck(true);
        assertThat(relay.runOnce()).as("cycle 2 publishes and marks exactly the one unmarked row").isEqualTo(1);
        assertThat(stack.outboxRowUnpublished(rowId)).isFalse();

        List<ConsumerRecord<byte[], byte[]>> copies = consumeRecordCopies(kafka.bootstrapServers(), aggregateId);
        assertThat(copies).as("the crashed relay must have put the record on the real broker twice (at least once)")
                .hasSize(2);
        for (ConsumerRecord<byte[], byte[]> copy : copies) {
            assertThat(copy.key()).as("the record key is the row's stored record_key").isNotNull();
            assertThat(new String(copy.key(), StandardCharsets.UTF_8)).isEqualTo(recordKey);
            assertThat(new String(copy.value(), StandardCharsets.UTF_8))
                    .as("records carry ids only (ADR-011 Decision 7): exactly {v, type, id}, no payload, no tenant id")
                    .isEqualTo(E8TestKafkaPublishPort.idsOnlyMessage(copy.topic(), aggregateId));
            assertThat(headersOf(copy)).as("the only header is the row's traceparent (E8-C5)")
                    .containsEntry("traceparent", traceparent).hasSize(1);
        }

        // then: the second is detected through the duplicate guard the consumer asks - zero rows claimed, and
        // nothing is sent twice (one delivery for two re-deliveries of the same row)
        JdbcDuplicateGuard guard = new JdbcDuplicateGuard(stack.appJdbcClient(),
                () -> java.util.Optional.of(E8DuplicateStack.TENANT_A));
        AtomicInteger deliveredWork = new AtomicInteger();
        boolean firstWasClaimed = stack.inTenantTx(E8DuplicateStack.TENANT_A, status -> {
            boolean duplicate = guard.isDuplicate(idempotencyKey);
            if (!duplicate) {
                deliveredWork.incrementAndGet(); // the consumer's work on the record
            }
            return duplicate;
        });
        assertThat(firstWasClaimed).as("record 1 passes the guard (the claim commits with the consumption)")
                .isFalse();

        boolean secondIsDetected = stack.inTenantTx(E8DuplicateStack.TENANT_A, status -> {
            boolean duplicate = guard.isDuplicate(idempotencyKey);
            if (!duplicate) {
                deliveredWork.incrementAndGet();
            }
            return duplicate;
        });
        assertThat(secondIsDetected).as("record 2 is detected - the same outbox row's claim is already committed")
                .isTrue();
        assertThat(deliveredWork.get()).as("nothing is sent twice: the target received the work exactly once")
                .isEqualTo(1);
        assertThat(stack.idempotencyClaimCount(idempotencyKey))
                .as("the redelivery claims zero rows: one committed claim per outbox row, the second ask changes nothing")
                .isEqualTo(1);

        // and the product's own pieces must take over these roles (the red-first seam checks; the missing piece
        // decides which wave 1 lane this red belongs to)
        E8RoutingSeams.assertRoutingsStageConsumerIsIntegrated();
        E8RoutingSeams.assertRoutingsPublishAdapterIsIntegrated();
    }

    /**
     * E8-C8's sibling clause, inside the duplicate suite: the chain is proven against a real Kafka in
     * Testcontainers - one broker, exactly the ADR-010 topics, the pinned {@code apache/kafka:4.3.1} image line,
     * and a produce/consume round trip through the real container. Green today: the wave 0 harness already gives
     * this; the duplicate scenario above rides the same real broker.
     */
    @Test
    @DisplayName("proven against a real Kafka in Testcontainers (E8-C8)")
    void theDuplicateChainIsProvenAgainstARealKafkaInTestcontainers() throws Exception {
        E8DuplicateStack stack = E8DuplicateStack.get();
        KafkaHarness kafka = KafkaHarness.get();
        assertThat(KafkaImages.KAFKA).as("the harness image is the pinned Kafka 4.3.1 (R12/R05)")
                .isEqualTo("apache/kafka:4.3.1");
        try (AdminClient admin = AdminClient.create(java.util.Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()))) {
            assertThat(admin.describeCluster().nodes().get(60, java.util.concurrent.TimeUnit.SECONDS))
                    .as("one real broker behind the harness").hasSize(1);
            assertThat(admin.listTopics().names().get(60, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the three ADR-010 topics on the real broker").contains(Topics.MESSAGE_ACCEPTED,
                            Topics.DELIVERY_DUE, Topics.POISON);
        }
        byte[] key = "e8-c8-round-trip".getBytes(StandardCharsets.UTF_8);
        byte[] value = "e8-c8-pinned-client-round-trip".getBytes(StandardCharsets.UTF_8);
        Properties producerProps = new Properties();
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers());
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000);
        Properties consumerProps = E8TestKafkaPublishPort.consumerProps(kafka.bootstrapServers(), "e8-c8-round-trip");
        try (org.apache.kafka.clients.producer.KafkaProducer<byte[], byte[]> producer =
                new org.apache.kafka.clients.producer.KafkaProducer<>(producerProps);
                KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps)) {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(Topics.MESSAGE_ACCEPTED, key, value))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            consumer.subscribe(List.of(Topics.MESSAGE_ACCEPTED));
            boolean received = false;
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            while (!received && System.currentTimeMillis() < deadline) {
                received = consumer.poll(Duration.ofSeconds(2)).records(Topics.MESSAGE_ACCEPTED).iterator().hasNext();
            }
            assertThat(received).as("the pinned client round trips the real broker: a record comes back").isTrue();
        }
    }

    private List<ConsumerRecord<byte[], byte[]>> consumeRecordCopies(String bootstrapServers, UUID aggregateId)
            throws InterruptedException {
        List<ConsumerRecord<byte[], byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(
                E8TestKafkaPublishPort.consumerProps(bootstrapServers, "e8-duplicate-lane-c1"))) {
            consumer.subscribe(List.of(Topics.MESSAGE_ACCEPTED));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis();
            while (found.size() < 2 && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofSeconds(2))
                        .records(Topics.MESSAGE_ACCEPTED)) {
                    String value = new String(record.value(), StandardCharsets.UTF_8);
                    if (value.endsWith("\"" + aggregateId + "\"}")) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static java.util.Map<String, String> headersOf(ConsumerRecord<byte[], byte[]> record) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        record.headers().forEach(header -> headers.put(header.key(), new String(header.value(),
                StandardCharsets.UTF_8)));
        return headers;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }

    private static String uuidHex(int length) {
        StringBuilder hex = new StringBuilder(length);
        while (hex.length() < length) {
            hex.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return hex.substring(0, length);
    }
}