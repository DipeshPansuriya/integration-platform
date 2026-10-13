package io.integrationplatform.routing.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.topics.Topics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * T-077 (E8-F2-S1) through the real Kafbat UI image pinned by {@link KafkaImages#KAFBAT}, running against the
 * {@link KafkaHarness} broker over one network:
 * <ul>
 * <li>"Topics are visible" - after logging in, the engineer sees the three work/poison topics plus consumer
 * groups and lag through the same REST payloads the Kafbat screen renders;</li>
 * <li>"{@code @negative @security} Kafbat is never tenant-facing" (login half) - before its own login an
 * API request is refused, after it the same request serves. The unreachable-from-the-tenant-facing-network
 * half is the compose layout test: Kafbat is on the operations network only.</li>
 * </ul>
 */
@Tag("container")
class E8F2KafbatViewContainerTest {

    private static final String CLUSTER_NAME = "platform";
    private static final String USER = "engineer";
    private static final String PASSWORD = "engineer-view-test-pass";
    private static final Duration TIMEOUT = Duration.ofMinutes(1);
    private static final Duration KAFBAT_START_TIMEOUT = Duration.ofMinutes(6);

    private static GenericContainer<?> kafbat;

    @BeforeAll
    static void startKafbatAgainstTheHarnessBroker() {
        KafkaHarness harness = KafkaHarness.get();
        kafbat = new GenericContainer<>(KafkaImages.KAFBAT)
                .withNetwork(harness.network())
                .withExposedPorts(8080)
                .waitingFor(Wait.forLogMessage(".*Started KafkaUiApplication.*", 1))
                .withStartupTimeout(KAFBAT_START_TIMEOUT)
                .withEnv("KAFKA_CLUSTERS_0_NAME", CLUSTER_NAME)
                .withEnv("KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS", "kafka:9095")
                .withEnv("AUTH_TYPE", "LOGIN_FORM")
                .withEnv("SPRING_SECURITY_USER_NAME", USER)
                .withEnv("SPRING_SECURITY_USER_PASSWORD", PASSWORD)
                .withEnv("SPRING_SECURITY_USER_ROLES", "ADMIN")
                .withEnv("DYNAMIC_CONFIG_ENABLED", "false");
        kafbat.start();
    }

    @AfterAll
    static void stopKafbat() {
        if (kafbat != null) {
            kafbat.stop();
        }
    }

    @Test
    void theEngineerMustLogInWithKafbatsOwnCredentials() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        String base = base();

        HttpResponse<Void> unauthenticated = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/clusters")).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(unauthenticated.statusCode())
                .as("an engineer who has not logged in gets no data: 401 (or a login redirect)")
                .isIn(401, 403, 302);

        String session = login(client, base);
        HttpResponse<Void> authenticated = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/clusters")).header("Cookie", session).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(authenticated.statusCode()).as("the same request after the Kafbat login").isEqualTo(200);
    }

    @Test
    void theWorkTopicsThePoisonTopicAndConsumerGroupsAreShown() throws Exception {
        String groupId = "e8-f2-engineer-view";
        createCommittedConsumerGroup(groupId);
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        String base = base();
        String session = login(client, base);

        String topics = pollUntil(client, base + "/api/clusters/" + CLUSTER_NAME + "/topics", session,
                Topics.MESSAGE_ACCEPTED);
        assertThat(topics)
                .as("the engineer sees the work topics and the poison topic in Kafbat (E8-C10)")
                .contains(Topics.MESSAGE_ACCEPTED, Topics.DELIVERY_DUE, Topics.POISON);

        // Kafbat 1.5.0 (the pinned digest) serves the grouped list the screen itself calls: /consumer-groups/paged.
        String groups = pollUntil(client, base + "/api/clusters/" + CLUSTER_NAME + "/consumer-groups/paged", session,
                groupId);
        assertThat(groups).as("the engineer sees consumer groups and their lag in Kafbat").contains(groupId);
        assertThat(groups).as("the consumer-groups payload carries the lag figure the screen shows")
                .contains("consumerLag");
    }

    /** Produces a few records and commits them with a real consumer group, so Kafbat has a group and lag to show. */
    private static void createCommittedConsumerGroup(String groupId) throws Exception {
        String bootstrap = KafkaHarness.get().bootstrapServers();
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000);
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps)) {
            for (int i = 0; i < 3; i++) {
                producer.send(new ProducerRecord<>(Topics.MESSAGE_ACCEPTED,
                        ("m-" + i).getBytes(), ("v-" + i).getBytes())).get();
            }
        }
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(Topics.MESSAGE_ACCEPTED));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                var records = consumer.poll(Duration.ofSeconds(2));
                if (!records.isEmpty()) {
                    consumer.commitSync();
                    consumer.unsubscribe();
                    return;
                }
            }
            throw new AssertionError("the group never consumed its records within 30 s");
        }
    }

    private static String base() {
        return "http://" + kafbat.getHost() + ":" + kafbat.getMappedPort(8080);
    }

    /** Posts the form login and returns the session cookie the screen itself would hold afterwards. */
    private static String login(HttpClient client, String base) throws IOException, InterruptedException {
        String form = "username=" + USER + "&password=" + PASSWORD;
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).as("the Kafbat login form accepts its own credentials").isIn(200, 302, 303);
        // Kafbat is a WebFlux application: the WebSession cookie is "SESSION" (not the servlet JSESSIONID).
        Optional<String> cookie = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.toLowerCase().startsWith("session=") || value.toLowerCase().startsWith("jsessionid="))
                .findFirst();
        assertThat(cookie.orElse("")).as("the login sets a session cookie").isNotEmpty();
        return cookie.get().split(";")[0];
    }

    /**
     * Polls an authenticated endpoint until it answers 200 <em>and</em> the body carries {@code mustContain}:
     * Kafbat answers 200 with an empty topic/group list while it is still connecting to the broker, so the
     * status code alone proves nothing.
     */
    private static String pollUntil(HttpClient client, String url, String session, String mustContain)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).header("Cookie", session).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                last = response.body();
                if (last.contains(mustContain)) {
                    return last;
                }
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError("Kafbat never served " + url + " containing '" + mustContain + "' within 2 minutes;"
                + " last body: " + last);
    }
}