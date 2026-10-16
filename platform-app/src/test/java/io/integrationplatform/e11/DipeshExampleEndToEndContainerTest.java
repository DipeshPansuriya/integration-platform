package io.integrationplatform.e11;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-132 - Dipesh's example as an end-to-end test (E11-F2-S1, backlog.md:3032-3063; E11-plan lane C1), the acceptance
 * of T106 / T-049 (intake -> Kafka -> Camel route -> transform -> API adapter):
 *
 * <pre>
 * Scenario: One call, two targets, two contract versions                         (T106 done_when 1)
 *   Given Target A (contract v1.0) and Target B (contract v2.0) onboarded as rows while the application runs
 *   When Customer A's credential posts {customerId, orderId, amount} to the common intake API
 *   Then Target A receives exactly 1 delivery {customer_code, order_number, total_amount}
 *   And Target B receives exactly 1 delivery {clientId, referenceNo, invoiceValue}
 *   And it took 0 deploys and 0 restarts (the same running application context handled it)
 *
 * Scenario: Two tenants run the example; tenant B never sees tenant A's rows      (T106 done_when 2)
 *   Given tenant A and tenant B each onboarded the example
 *   When both post
 *   Then a session stamped for tenant B reads 0 of tenant A's message, payload, delivery and timing rows
 *
 * Scenario: A failing target lands in the dead-letter path, the other tenant's flow unharmed  (done_when 3)
 *   Given tenant B's Target B answers 400 (a PERMANENT refusal)
 *   Then that delivery is DEAD_LETTERED (PERMANENT) after exactly one attempt
 *   And tenant A's two deliveries and tenant B's Target A delivery are DELIVERED
 * </pre>
 *
 * <p>The whole application runs: PostgreSQL, Keycloak (the source systems' client-credentials tokens, ADR-007
 * Decision 3) and one Kafka broker carrying the three ADR-010 topics (auto-creation off). The targets are
 * {@link E11TargetStub}; the suite's test network resolves {@code *.e11.test} to the TEST-NET-3 address
 * {@value #PINNED_TEXT}, which the destination guard accepts and pins, and its dialer connects only that address to
 * the stub - no test can reach a real host. Each wait names the pipeline link it waits on, so a red run says which
 * link is missing (relay, stage 1, stage 2, target).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(DipeshExampleEndToEndContainerTest.TestNetwork.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E11-F2-S1 Dipesh's example works as one system (T-132, T106)")
class DipeshExampleEndToEndContainerTest {

    private static final String PINNED_TEXT = "203.0.113.10";
    private static final int TARGET_PORT = 8080;
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    /** Only source systems call in this suite (client credentials); no console login, so the redirects stay inert. */
    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            "http://localhost/login/oauth2/code/keycloak", "http://localhost/");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static final E11TargetStub TARGETS = E11TargetStub.start();

    private static RestrictedLogin login;
    private static DipeshExampleSeed.Tenant tenantA;
    private static DipeshExampleSeed.Tenant tenantB;
    private static ClientCredentials sourceA;
    private static ClientCredentials sourceB;
    private static UUID messageA;
    private static UUID messageB;
    private static ApplicationContext contextAtOnboarding;
    private static long startupDateAtOnboarding;
    private static URI backend;

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start(); // idempotent; the issuer must be known before the context starts
        KAFKA.start();
        createTheThreeTopics(KAFKA.getBootstrapServers());
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_URL", () -> "/");
        // the Kafka backbone of the deployment (R07: environment variable names, values per run)
        registry.add("IPASS_KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("IPASS_KAFKA_PUBLISH_CLIENT_ID", () -> "e11-t132-relay");
        // the operator's settings the example needs: the target hosts and the endpoint URI schemes it may reach
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.e11.test");
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    @BeforeEach
    void onboardWhileRunningThenPostOnce() throws Exception {
        if (tenantA != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        backend = URI.create("http://localhost:" + port);
        contextAtOnboarding = context;
        startupDateAtOnboarding = context.getStartupDate();
        sourceA = KEYCLOAK.createConfidentialClient("e11-shop-a-" + UUID.randomUUID().toString().substring(0, 8),
                true);
        sourceB = KEYCLOAK.createConfidentialClient("e11-shop-b-" + UUID.randomUUID().toString().substring(0, 8),
                true);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenantA = DipeshExampleSeed.onboard(owner, "alpha", "CUST-A", sourceA.clientId(), KEYCLOAK.issuerUri());
            tenantB = DipeshExampleSeed.onboard(owner, "bravo", "CUST-B", sourceB.clientId(), KEYCLOAK.issuerUri());
        }
        // tenant B's Target B refuses: a 400 is PERMANENT (ADR-010 section 5) - the dead-letter leg
        TARGETS.answer(tenantB.pathB(), 400);
        messageA = post(sourceA, "{\"customerId\":\"CUST-A\",\"orderId\":\"ORD-A-1\",\"amount\":1250.5}");
        messageB = post(sourceB, "{\"customerId\":\"CUST-B\",\"orderId\":\"ORD-B-1\",\"amount\":99.95}");
    }

    @AfterAll
    static void stopTheTargets() {
        TARGETS.stop();
    }

    @Test
    @Order(1)
    @DisplayName("one call reaches both configured targets contract-translated - 0 deploys, 0 restarts")
    void oneCallReachesBothTargetsContractTranslated() throws Exception {
        awaitPipeline(messageA, 2);

        List<E11TargetStub.Received> atA = TARGETS.receivedOn(tenantA.pathA());
        List<E11TargetStub.Received> atB = TARGETS.receivedOn(tenantA.pathB());
        assertThat(atA).as("Target A receives exactly 1 delivery").hasSize(1);
        assertThat(atB).as("Target B receives exactly 1 delivery").hasSize(1);
        assertThat(JSON.readTree(atA.get(0).body())).as("Target A's contract v1.0")
                .isEqualTo(JSON.readTree("{\"customer_code\":\"CUST-A\",\"order_number\":\"ORD-A-1\","
                        + "\"total_amount\":1250.5}"));
        assertThat(JSON.readTree(atB.get(0).body())).as("Target B's contract v2.0")
                .isEqualTo(JSON.readTree("{\"clientId\":\"CUST-A\",\"referenceNo\":\"ORD-A-1\","
                        + "\"invoiceValue\":1250.5}"));
        assertThat(atA.get(0).contentType()).startsWith("application/json");

        Map<UUID, String> states = deliveryStatesOf(messageA);
        assertThat(states).as("both deliveries of tenant A's message").hasSize(2).containsValues("DELIVERED")
                .doesNotContainValue("DEAD_LETTERED");
        assertThat(states.values()).containsOnly("DELIVERED");
        // each target call is addressed per delivery (ADR-011 Decision 4): the key is the delivery id
        assertThat(List.of(atA.get(0).idempotencyKey(), atB.get(0).idempotencyKey()))
                .containsExactlyInAnyOrderElementsOf(states.keySet().stream().map(UUID::toString).toList());

        assertThat(context).as("the same running application handled the onboarded flow").isSameAs(
                contextAtOnboarding);
        assertThat(context.getStartupDate()).as("no restart since the rows were inserted")
                .isEqualTo(startupDateAtOnboarding);
    }

    @Test
    @Order(2)
    @DisplayName("two tenants run the example; tenant B never sees tenant A's message, delivery or timing rows")
    void tenantBNeverSeesTenantARows() throws Exception {
        awaitPipeline(messageA, 2);
        awaitPipeline(messageB, 2);

        for (String table : List.of("message", "payload", "delivery", "message_stage_timing")) {
            String idColumn = table.equals("message") ? "id" : "message_id";
            assertThat(countAs(tenantA, table, idColumn, messageA)).as("tenant A sees its own %s rows", table)
                    .isPositive();
            assertThat(countAs(tenantB, table, idColumn, messageA)).as("tenant B reads tenant A's %s rows", table)
                    .isZero();
            assertThat(countAs(tenantA, table, idColumn, messageB)).as("tenant A reads tenant B's %s rows", table)
                    .isZero();
        }
        assertThat(TARGETS.receivedOn(tenantB.pathA())).as("tenant B's own flow reached its Target A").hasSize(1);
        assertThat(JSON.readTree(TARGETS.receivedOn(tenantB.pathA()).get(0).body()))
                .isEqualTo(JSON.readTree("{\"customer_code\":\"CUST-B\",\"order_number\":\"ORD-B-1\","
                        + "\"total_amount\":99.95}"));
        assertThat(TARGETS.receivedOn(tenantA.pathA())).as("nothing of tenant B reached tenant A's target")
                .allSatisfy(received -> assertThat(received.body()).doesNotContain("CUST-B"));
    }

    @Test
    @Order(3)
    @DisplayName("a failing target lands its delivery in the dead-letter path without harming the other tenant")
    void aFailingTargetDeadLettersWithoutHarmingTheOtherTenant() throws Exception {
        awaitPipeline(messageA, 2);
        awaitPipeline(messageB, 2);

        Map<String, Object> failed = deliveryToEndpoint(messageB, tenantB.endpointB());
        assertThat(failed.get("state")).as("tenant B's refused delivery").isEqualTo("DEAD_LETTERED");
        assertThat(failed.get("dead_letter_class")).isEqualTo("PERMANENT");
        assertThat(attemptsOf((UUID) failed.get("id"))).as("a PERMANENT answer is never retried").isEqualTo(1);
        assertThat(TARGETS.receivedOn(tenantB.pathB())).as("the refusing target was called once").hasSize(1);

        assertThat(deliveryToEndpoint(messageB, tenantB.endpointA()).get("state"))
                .as("tenant B's other target is unharmed").isEqualTo("DELIVERED");
        assertThat(deliveryStatesOf(messageA).values()).as("tenant A's flow is unharmed").containsOnly("DELIVERED");
    }

    // --- the pipeline, link by link (each wait names the link it waits on) ------------------------------------------

    private static void awaitPipeline(UUID messageId, int deliveries) throws Exception {
        await("the outbox relay to publish message " + messageId + "'s platform.message.accepted row to Kafka"
                        + " (intake -> outbox -> relay -> Kafka)",
                () -> ownerCount("select count(*) from public.outbox where aggregate_id = ?::uuid"
                        + " and topic = 'platform.message.accepted' and published_at is not null", messageId) == 1);
        await("stage 1 to route message " + messageId + " into " + deliveries + " deliveries (message.accepted"
                        + " consumer -> fan-out)",
                () -> "ROUTED".equals(ownerString("select state from public.message where id = ?::uuid", messageId))
                        && ownerCount("select count(*) from public.delivery where message_id = ?::uuid",
                                messageId) == deliveries);
        await("stage 2 to settle every delivery of message " + messageId + " (delivery.due consumer -> claim ->"
                        + " route -> transform -> API adapter)",
                () -> ownerCount("select count(*) from public.delivery where message_id = ?::uuid"
                        + " and state in ('DELIVERED', 'DEAD_LETTERED')", messageId) == deliveries);
    }

    private static void await(String link, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("waited " + WAIT.toSeconds() + " s for " + link + " - it never happened");
    }

    private static UUID post(ClientCredentials source, String body) throws Exception {
        String token = KEYCLOAK.clientCredentialsToken(source.clientId(), source.secret());
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(
                        backend.resolve("/api/v1/interfaces/" + DipeshExampleSeed.INTERFACE_CODE + "/messages"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", DipeshExampleSeed.SOURCE_CONTRACT_LABEL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("the common intake API accepts: %s", answer.body()).isEqualTo(202);
        JsonNode tracking = JSON.readTree(answer.body()).path("trackingId");
        return UUID.fromString(tracking.asString());
    }

    // --- reads: the owner sees everything (observation only); the app login proves row-level security ----------

    private static int countAs(DipeshExampleSeed.Tenant stamped, String table, String idColumn, UUID messageId)
            throws Exception {
        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), login.username(),
                login.password())) {
            app.setAutoCommit(false);
            try (PreparedStatement stamp = app.prepareStatement("select set_config('app.tenant_id', ?, true)");
                    PreparedStatement count = app.prepareStatement(
                            "select count(*) from public." + table + " where " + idColumn + " = ?::uuid")) {
                stamp.setString(1, stamped.id().toString());
                stamp.execute();
                count.setString(1, messageId.toString());
                try (ResultSet rows = count.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            } finally {
                app.rollback();
            }
        }
    }

    private static Map<UUID, String> deliveryStatesOf(UUID messageId) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement read = owner.prepareStatement(
                        "select id, state from public.delivery where message_id = ?::uuid")) {
            read.setString(1, messageId.toString());
            Map<UUID, String> states = new java.util.LinkedHashMap<>();
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    states.put(rows.getObject(1, UUID.class), rows.getString(2));
                }
            }
            return states;
        }
    }

    private static Map<String, Object> deliveryToEndpoint(UUID messageId, UUID endpointId) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement read = owner.prepareStatement("select id, state, dead_letter_class"
                        + " from public.delivery where message_id = ?::uuid and target_id = ?::uuid")) {
            read.setString(1, messageId.toString());
            read.setString(2, endpointId.toString());
            try (ResultSet rows = read.executeQuery()) {
                assertThat(rows.next()).as("a delivery of message %s to endpoint %s", messageId, endpointId)
                        .isTrue();
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("id", rows.getObject("id", UUID.class));
                row.put("state", rows.getString("state"));
                row.put("dead_letter_class", rows.getString("dead_letter_class"));
                return row;
            }
        }
    }

    private static int attemptsOf(UUID deliveryId) throws Exception {
        return ownerCount("select count(*) from public.delivery_attempt where delivery_id = ?::uuid", deliveryId);
    }

    private static int ownerCount(String sql, UUID id) {
        String value = ownerString(sql, id);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static String ownerString(String sql, UUID id) {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement read = owner.prepareStatement(sql)) {
            read.setString(1, id.toString());
            try (ResultSet rows = read.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("owner read failed: " + e.getMessage(), e);
        }
    }

    // --- infrastructure ---------------------------------------------------------------------------------------------

    private static void createTheThreeTopics(String bootstrapServers) {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers))) {
            List<NewTopic> topics = new ArrayList<>();
            for (String topic : List.of("platform.message.accepted", "platform.delivery.due", "platform.poison")) {
                topics.add(new NewTopic(topic, 3, (short) 1));
            }
            admin.createTopics(topics).all().get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new IllegalStateException("the three ADR-010 topics could not be created: " + e.getMessage(), e);
            }
        }
    }

    /**
     * The suite's test network (the {@code ApiAdapterWireMock} pattern): {@code *.e11.test} resolves to the
     * documentation address the guard accepts and pins; the dialer connects only that pinned address and port to the
     * in-process targets and refuses everything else, so the guard's pin is what is dialled and no real host is ever
     * reachable.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestNetwork {

        @Bean
        @Primary
        HostResolver e11TargetNames() {
            return host -> {
                if (host.endsWith(".e11.test")) {
                    return List.of(InetAddress.getByName(PINNED_TEXT)); // a literal, never a DNS query
                }
                throw new UnknownHostException(host);
            };
        }

        @Bean
        @Primary
        SocketDialer e11TargetDialer() {
            return (address, connectTimeoutMillis) -> {
                if (!address.getAddress().getHostAddress().equals(PINNED_TEXT) || address.getPort() != TARGET_PORT) {
                    throw new ConnectException("the test network has no route to " + address);
                }
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), TARGETS.port()),
                        connectTimeoutMillis);
                return socket;
            };
        }
    }
}
