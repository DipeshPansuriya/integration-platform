package io.integrationplatform.e10;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.IntegrationPlatformApplication;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.credential.api.KeyAvailability;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-201 (E10-F15) criterion 2 with two real instances of the whole application on one PostgreSQL and one Kafka
 * broker (no fakes): instance A loses a key (readiness DOWN), so its stage 1 consumer holds its partitions and
 * commits nothing; instance B is ready and works its own; once A leaves, B takes A's partitions and the held
 * records are worked - nothing was skipped while A was not ready.
 *
 * <p>The records are unreadable on purpose: an unreadable record is written to poison once and its offset committed
 * (StageConsumer), so a committed stage 1 offset proves the record was worked without onboarding a tenant.
 */
@Tag("container")
@Testcontainers
@DisplayName("E10-F15 readiness DOWN stops one instance's consumers; the ready instance carries the work (T-201)")
class InstanceReadinessTwoInstanceContainerTest {

    private static final String STAGE1_GROUP = "platform-stage1";
    private static final String ACCEPTED = "platform.message.accepted";
    private static final int PARTITIONS = 3;
    private static final Duration HOLD = Duration.ofSeconds(10);
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private static final Map<String, Object> PROPERTIES = new HashMap<>();
    private static PlatformTestWiring.RestrictedLogin login;
    private static Admin admin;
    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    @BeforeAll
    static void sharedBackbone() throws Exception {
        Map<String, Supplier<Object>> registered = new HashMap<>();
        login = PlatformTestWiring.register(registered::put, POSTGRES);
        registered.forEach((name, value) -> PROPERTIES.put(name, value.get()));
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        PROPERTIES.put("PLATFORM_CREDENTIAL_KEK_K2", Base64.getEncoder().encodeToString(key));
        PROPERTIES.put("PLATFORM_CREDENTIAL_KEK_ACTIVE", "K2");
        PROPERTIES.put("IPASS_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
        PROPERTIES.put("CONSOLE_URL", "/");
        PROPERTIES.put("server.port", "0");
        admin = Admin.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(ACCEPTED, PARTITIONS, (short) 1),
                new NewTopic("platform.delivery.due", PARTITIONS, (short) 1),
                new NewTopic("platform.poison", 1, (short) 1))).all().get();
    }

    @AfterAll
    static void stop() {
        for (ConfigurableApplicationContext instance : new ConfigurableApplicationContext[] {instanceA, instanceB}) {
            if (instance != null) {
                instance.close();
            }
        }
        if (admin != null) {
            admin.close();
        }
    }

    private static ConfigurableApplicationContext start(String publishClientId) {
        Map<String, Object> properties = new HashMap<>(PROPERTIES);
        properties.put("IPASS_KAFKA_PUBLISH_CLIENT_ID", publishClientId);
        return new SpringApplicationBuilder(IntegrationPlatformApplication.class).properties(properties).run();
    }

    @Test
    @Tag("negative")
    @DisplayName("A not ready commits nothing; B ready works its partitions; A gone, B works the held records")
    void readinessDownHoldsTheWorkForTheReadyInstance() throws Exception {
        instanceA = start("t201-instance-a");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        instanceA.getBean(KeyAvailability.class).reportMissing("K-ABSENT");

        publishOneUnreadableRecordPerPartition();
        Thread.sleep(HOLD.toMillis());
        assertThat(committedPartitions()).as("instance A is not ready: no stage 1 offset is committed").isZero();

        instanceB = start("t201-instance-b");
        await(() -> committedPartitions() >= 1, "instance B (ready) works the partitions it was assigned");
        Thread.sleep(HOLD.toMillis());
        assertThat(committedPartitions()).as("the partitions instance A holds stay uncommitted")
                .isLessThan(PARTITIONS);

        instanceA.close();
        instanceA = null;
        await(() -> committedPartitions() == PARTITIONS, "instance B takes over the held partitions and works them");
    }

    private static void publishOneUnreadableRecordPerPartition() throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(config)) {
            for (int partition = 0; partition < PARTITIONS; partition++) {
                producer.send(new ProducerRecord<>(ACCEPTED, partition, null,
                        ("not-a-work-record-" + partition).getBytes(StandardCharsets.UTF_8))).get();
            }
        }
    }

    private static int committedPartitions() {
        try {
            Map<?, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(STAGE1_GROUP)
                    .partitionsToOffsetAndMetadata().get();
            return (int) offsets.values().stream().filter(o -> o != null && o.offset() > 0).count();
        } catch (Exception e) {
            throw new IllegalStateException("could not read the stage 1 committed offsets", e);
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting: " + what + " (committed " + committedPartitions() + ")");
            }
            Thread.sleep(500);
        }
    }
}
