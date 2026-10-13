package io.integrationplatform.routing.e8.clientpin;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.e8.duplicate.E8RoutingSeams;
import io.integrationplatform.routing.kafka.KafkaHarness;
import io.integrationplatform.routing.kafka.KafkaImages;
import io.integrationplatform.routing.topics.Topics;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-115 (E8-F1-S2, traces E8-C8): "The pinned client works at runtime". Three proofs in one suite:
 * <ol>
 * <li>the resolved routing classpath holds exactly ONE {@code org.apache.kafka:kafka-clients} version (the pom's
 * enforcer pin kept at the runtime resolution too) and it is the phase 0 pin {@code 4.3.1}; spring-kafka is not
 * on the classpath anywhere (the {@code bannedDependencies} ban, R12),</li>
 * <li>that pinned client produces to and consumes from a REAL broker - the wave 0 harness's
 * {@code apache/kafka:4.3.1} Testcontainers container (E8-C8),</li>
 * <li>the red-first seam check: the product's runtime use of the pinned client - the Kafka publish adapter behind
 * the ingest {@code PublishPort} in {@code routing/publish} (lane B, T-110) - is not integrated yet, so this
 * suite stays red until the wave 1 integration; arjun-fullstack re-runs it then and it turns green.</li>
 * </ol>
 */
@Tag("container")
class E8PinnedClientRuntimeTest {

    @Test
    @DisplayName("The pinned client works at runtime")
    void thePinnedClientWorksAtRuntime() throws Exception {
        // the dependency resolution: exactly one kafka-clients version, and it is the phase 0 pin
        java.util.List<String> versions = kafkaClientsVersionsOnTheClasspath();
        assertThat(versions)
                .as("the resolved classpath shows exactly one org.apache.kafka:kafka-clients version")
                .containsExactly("4.3.1");
        assertThat(KafkaProducer.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .as("the pinned client the JVM actually loads kafka-clients 4.3.1 (R12)")
                .contains("kafka-clients-4.3.1.jar");
        assertThat(getClass().getClassLoader().getResource("org/springframework/kafka/core/KafkaTemplate.class"))
                .as("spring-kafka is banned - it never appears on the routing classpath (R12)")
                .isNull();

        // the pinned client at runtime: a real broker round trip (E8-C8), through the wave 0 harness's container
        KafkaHarness kafka = KafkaHarness.get();
        assertThat(KafkaImages.KAFKA).as("the broker is the pinned kafka-clients-matching image (R12)")
                .isEqualTo("apache/kafka:4.3.1");

        byte[] key = "e8-pinned-runtime-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] value = "e8-pinned-runtime-value".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000);
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "e8-pinned-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps);
                KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps)) {
            producer.send(new ProducerRecord<>(Topics.DELIVERY_DUE, key, value)).get(30,
                    java.util.concurrent.TimeUnit.SECONDS);
            consumer.subscribe(List.of(Topics.DELIVERY_DUE));
            boolean roundTrip = false;
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            while (!roundTrip && System.currentTimeMillis() < deadline) {
                for (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record :
                        consumer.poll(Duration.ofSeconds(2)).records(Topics.DELIVERY_DUE)) {
                    roundTrip |= java.util.Arrays.equals(record.key(), key)
                            && java.util.Arrays.equals(record.value(), value);
                }
            }
            assertThat(roundTrip).as("the pinned kafka-clients 4.3.1 works at runtime against the real broker")
                    .isTrue();
        }

        // the product's runtime use of the pinned client (the red-first seam check): the Kafka publish adapter
        // behind the publish port, which lane B (T-110) integrates in wave 1
        E8RoutingSeams.assertRoutingsPublishAdapterIsIntegrated();
    }

    /**
     * Test-scoped dependency resolution: every {@code kafka-clients-<version>.jar} the resolved routing test
     * classpath carries. Surefire runs the fork with a manifest-only booter jar, so the real classpath is read
     * from that jar's {@code Class-Path} manifest attribute as well.
     */
    private static java.util.List<String> kafkaClientsVersionsOnTheClasspath() throws Exception {
        java.util.Set<String> entries = new java.util.LinkedHashSet<>();
        if (System.getProperty("java.class.path") != null) {
            java.util.Collections.addAll(entries, System.getProperty("java.class.path")
                    .split(java.io.File.pathSeparator));
        }
        for (String entry : new java.util.ArrayList<>(entries)) {
            if (entry.endsWith(".jar")) {
                try (java.util.jar.JarFile booter = new java.util.jar.JarFile(entry)) {
                    String manifestClassPath = booter.getManifest() == null ? null
                            : booter.getManifest().getMainAttributes()
                                    .getValue(java.util.jar.Attributes.Name.CLASS_PATH);
                    if (manifestClassPath != null) {
                        java.util.Collections.addAll(entries, manifestClassPath.split(" "));
                    }
                }
            }
        }
        java.util.List<String> versions = new java.util.ArrayList<>();
        for (String entry : entries) {
            String name = entry.replace('\\', '/');
            int start = name.indexOf("kafka-clients-");
            if (start < 0 || !name.endsWith(".jar")) {
                continue;
            }
            versions.add(name.substring(start + "kafka-clients-".length(), name.length() - ".jar".length()));
        }
        java.util.Collections.sort(versions);
        return versions;
    }
}