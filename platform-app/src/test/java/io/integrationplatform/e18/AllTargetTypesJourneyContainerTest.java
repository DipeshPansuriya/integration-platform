package io.integrationplatform.e18;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.app.report.ReportFacade;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T-151 (E18-F1-S1): the whole journey once per target type - intake, route, transform, deliver - on real containers,
 * with the W3C traceparent carried end to end and the tracking and report reads showing the journey:
 *
 * <pre>
 * Scenario Outline: The journey per target type            (API | MQ-CSV | Kafka topic | DB | File)
 *   Given a tenant onboarded with one target of the type, as rows, while the application runs
 *   When its source posts {customerId, orderId, amount} with a traceparent
 *   Then the partner receives exactly one delivery in its own format
 *   And the traceparent is the Kafka header of the accepted and due records
 *   And the delivery is DELIVERED, and the tracking timeline and the report show INTAKE..DELIVER
 * </pre>
 *
 * Written against the adapter contract (settings shapes in {@code routing.adapter.*}). A scenario that can fail only
 * because the e16-adapters lane is not merged carries {@link BlockedBy} naming the exact assertion; none is weakened.
 */
@DisplayName("E18-F1-S1 every target type passes the whole journey (T-151)")
class AllTargetTypesJourneyContainerTest extends E18Base {

    private static final String TRACE_ID = "7d3e5f0a1b2c4d6e8f90a1b2c3d4e5f6";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-0123456789abcdef-01";

    @Autowired
    MessageTrackingService tracking;

    @Autowired
    ReportFacade reports;

    /** One journey's handles. */
    private record Run(E18Seed.Tenant tenant, UUID message, String orderId, Map<UUID, String> deliveries) {
    }

    private Run journey(String word, String systemType, String settings, String baseUrl, boolean idempotent)
            throws Exception {
        return journey(word, systemType, settings, baseUrl, idempotent, tenant -> { });
    }

    private Run journey(String word, String systemType, String settings, String baseUrl, boolean idempotent,
            java.util.function.Consumer<UUID> beforePost) throws Exception {
        grantAppRoleOnce();
        ClientCredentials source = newSource("e18-" + word);
        String customer = "CUST-" + word.toUpperCase();
        E18Seed.Tenant tenant;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenant = E18Seed.onboard(owner, word, customer, source.clientId(), KEYCLOAK.issuerUri(), systemType,
                    settings, baseUrl, idempotent);
        }
        beforePost.accept(tenant.id());
        String orderId = "ORD-" + word.toUpperCase() + "-1";
        UUID message = post(source, E18Seed.INTERFACE_CODE,
                "{\"customerId\":\"" + customer + "\",\"orderId\":\"" + orderId + "\",\"amount\":12.5}", TRACEPARENT);
        awaitSettled(message, 1);
        return new Run(tenant, message, orderId, deliveryStates(message));
    }

    /** The shared assertions: the delivery is DELIVERED, the trace id is on the Kafka headers, the reads show it. */
    private void assertJourneyReads(Run run) {
        assertThat(run.deliveries().values()).as("the one delivery reached its target; last attempt: %s",
                lastAttemptDetail(run.message())).containsExactly("DELIVERED");

        Map<String, List<String>> headers = traceparentHeaders(
                List.of(run.message().toString(), run.deliveries().keySet().iterator().next().toString()));
        headers.values().forEach(values -> assertThat(values).as("traceparent Kafka header").isNotEmpty()
                .containsOnly(TRACEPARENT));
        assertThat(headers).as("both the accepted and the due record were seen").hasSize(2);

        MessageTrackingService.Timeline timeline = readTenant.as(run.tenant().id(),
                () -> tracking.timeline(run.message()).orElseThrow(() -> new AssertionError(
                        "the tenant's tracking read cannot see its own message " + run.message())));
        assertThat(timeline.stages()).extracting(MessageTrackingService.StageTime::stage)
                .as("tracking timeline stages").contains("INTAKE", "ROUTE", "TRANSFORM", "DELIVER");
        assertThat(timeline.failingStage()).as("no failing stage").isNull();
        assertThat(timeline.deliveries()).hasSize(1);
        assertThat(timeline.deliveries().get(0).state().toString()).isEqualTo("DELIVERED");

        UUID flow = UUID.fromString(timeline.flowId());
        ReportFacade.Report report = readTenant.as(run.tenant().id(),
                () -> reports.report(run.message(), flow, LocalDate.now(ZoneOffset.UTC)));
        assertThat(report.timeline()).as("the report carries the same journey").isNotNull();
        assertThat(report.timeline().trackingId()).isEqualTo(run.message().toString());
        assertThat(report.failures()).as("a delivered message is not in the failure list").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ API

    @Test
    @DisplayName("API: the partner receives one contract-translated POST carrying the delivery id and the trace id")
    void apiTargetJourney() throws Exception {
        String path = "/api-journey/orders";
        Run run = journey("api", "API", "{\"baseUrl\":\"http://api.e18.test:" + PARTNER.port() + path + "\"}",
                "http://api.e18.test:" + PARTNER.port() + path, true);
        assertJourneyReads(run);

        List<E18TargetStub.Received> received = PARTNER.receivedOn(path);
        assertThat(received).as("the API partner received exactly one delivery").hasSize(1);
        assertThat(JSON.readTree(received.get(0).body())).isEqualTo(JSON.readTree(
                "{\"order_no\":\"ORD-API-1\",\"customer\":\"CUST-API\",\"total\":12.5}"));
        assertThat(received.get(0).idempotencyKey()).as("addressed per delivery")
                .isEqualTo(run.deliveries().keySet().iterator().next().toString());
        assertThat(received.get(0).traceparent()).as("the trace id reaches the partner's request")
                .contains(TRACE_ID);
    }

    // --------------------------------------------------------------------------------------------------- MQ-CSV

    @Test
    @DisplayName("MQ-CSV: a CSV in the tenant's contract lands once on the partner's queue")
    void mqCsvTargetJourney() throws Exception {
        String queue = "e18.orders.mqcsv";
        String settings = "{\"host\":\"localhost\",\"port\":" + ARTEMIS.getMappedPort(ARTEMIS_PORT)
                + ",\"queue\":\"" + queue + "\",\"contract\":{\"columns\":[{\"name\":\"order_no\",\"type\":\"text\"},"
                + "{\"name\":\"customer\",\"type\":\"text\"},{\"name\":\"total\",\"type\":\"number\"}],"
                + "\"delimiter\":\",\",\"quote\":\"\\\"\",\"quoteAll\":false,\"encoding\":\"UTF-8\","
                + "\"lineEnding\":\"CRLF\",\"header\":true}}";
        Run run = journey("mqcsv", "MQ", settings, "mq://mq.e18.test/" + queue, true);
        assertJourneyReads(run);

        List<String> messages = drainQueue(queue);
        assertThat(messages).as("exactly one message on the partner's queue (no duplicate)").hasSize(1);
        assertThat(messages.get(0)).as("the body is the tenant's CSV contract, not the transform's JSON")
                .isEqualTo("order_no,customer,total\r\nORD-MQCSV-1,CUST-MQCSV,12.5\r\n");
    }

    // ------------------------------------------------------------------------------------------------ Kafka topic

    @Test
    @DisplayName("Kafka topic: the partner's own topic receives one record keyed by the delivery id")
    void kafkaTopicTargetJourney() throws Exception {
        String topic = "e18.partner.orders";
        String settings = "{\"bootstrapServers\":\"localhost:" + TARGET_KAFKA.getMappedPort(9092)
                + "\",\"topic\":\"" + topic + "\",\"securityProtocol\":\"PLAINTEXT\"}"; // R1: absent = SSL; the Testcontainers broker is clear text
        Run run = journey("kafka", "KAFKA", settings, "kafka://kafka.e18.test/" + topic, true);
        assertJourneyReads(run);

        List<ConsumerRecord<String, String>> records = readTopic(topic, 1);
        assertThat(records).as("exactly one record on the partner's topic").hasSize(1);
        assertThat(records.get(0).key()).as("the delivery id is the record key")
                .isEqualTo(run.deliveries().keySet().iterator().next().toString());
        assertThat(JSON.readTree(records.get(0).value())).isEqualTo(JSON.readTree(
                "{\"order_no\":\"ORD-KAFKA-1\",\"customer\":\"CUST-KAFKA\",\"total\":12.5}"));
    }

    // ----------------------------------------------------------------------------------------------------- DB

    /**
     * The F3 approval of {@code partner_orders} for the tenant's DB system, through the real admin API: a CONFIGURATOR
     * requests the table, a DIFFERENT user (APPROVER) approves it (E16 F3 D4); no row is inserted by the owner.
     */
    private void approvePartnerOrders(UUID tenantId) {
        try {
            String systemId = ownerString("select id from public.system where tenant_id = ?::uuid and type = 'DB'",
                    tenantId);
            String path = "/api/v1/admin/systems/" + systemId + "/approved-tables";
            String configurator = adminUserToken(tenantId, "CONFIGURATOR");
            String approver = adminUserToken(tenantId, "APPROVER");

            HttpResponse<String> requested = adminCall(path, configurator, "{\"table\":\"partner_orders\"}");
            assertThat(requested.statusCode()).as("request: %s", requested.body()).isEqualTo(201);
            String rowId = JSON.readTree(requested.body()).path("id").asString();
            HttpResponse<String> approved = adminCall(path + "/" + rowId + "/approve", approver, null);
            assertThat(approved.statusCode()).as("approve: %s", approved.body()).isEqualTo(200);
            assertThat(JSON.readTree(approved.body()).path("status").asString()).isEqualTo("APPROVED");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Keycloak service-account token whose subject is registered as a user of the tenant with one role. */
    private String adminUserToken(UUID tenantId, String role) throws Exception {
        ClientCredentials client = newSource("e18-" + role.toLowerCase());
        String token = KEYCLOAK.clientCredentialsToken(client.clientId(), client.secret());
        var claims = JSON.readTree(new String(java.util.Base64.getUrlDecoder().decode(token.split("[.]")[1]),
                StandardCharsets.UTF_8));
        UUID userId = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                java.sql.PreparedStatement user = owner.prepareStatement("insert into app_user (id, tenant_id, issuer,"
                        + " subject, status) values (?::uuid, ?::uuid, ?, ?, 'ACTIVE')");
                java.sql.PreparedStatement grant = owner.prepareStatement(
                        "insert into role_grant (tenant_id, user_id, role) values (?::uuid, ?::uuid, ?)")) {
            user.setString(1, userId.toString());
            user.setString(2, tenantId.toString());
            user.setString(3, claims.path("iss").asString());
            user.setString(4, claims.path("sub").asString());
            user.executeUpdate();
            grant.setString(1, tenantId.toString());
            grant.setString(2, userId.toString());
            grant.setString(3, role);
            grant.executeUpdate();
        }
        return token;
    }

    private HttpResponse<String> adminCall(String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(backend().resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return HTTP.send(request.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("DB: one row lands in the partner's approved table")
    void dbTargetJourney() throws Exception {
        try (Connection partner = java.sql.DriverManager.getConnection(TARGET_DB.getJdbcUrl(),
                TARGET_DB.getUsername(), TARGET_DB.getPassword()); Statement create = partner.createStatement()) {
            create.execute("create table if not exists partner_orders (order_no text, customer text, total numeric, delivery_id uuid unique)");
        }
        String settings = "{\"host\":\"db.e18.test\",\"port\":" + TARGET_DB.getMappedPort(5432) + ",\"database\":\""
                + TARGET_DB.getDatabaseName() + "\",\"user\":\"" + TARGET_DB.getUsername()
                + "\",\"table\":\"partner_orders\"}";
        // E16 F3: the settings no longer carry approvedTables (the API refuses the key, FIELD_NOT_ALLOWED); the table
        // is requested by one user and approved by another, as an APPROVED row bound to the system's fingerprint
        Run run = journey("db", "DB", settings, "jdbc:postgresql://db.e18.test/" + TARGET_DB.getDatabaseName(), true,
                this::approvePartnerOrders);
        assertJourneyReads(run);

        try (Connection partner = java.sql.DriverManager.getConnection(TARGET_DB.getJdbcUrl(),
                TARGET_DB.getUsername(), TARGET_DB.getPassword()); Statement read = partner.createStatement();
                ResultSet rows = read.executeQuery("select order_no, customer, total from partner_orders"
                        + " where order_no = 'ORD-DB-1'")) {
            assertThat(rows.next()).as("the row landed in partner_orders").isTrue();
            assertThat(rows.getString("customer")).isEqualTo("CUST-DB");
            assertThat(rows.getBigDecimal("total")).isEqualByComparingTo("12.5");
            assertThat(rows.next()).as("exactly one row (no duplicate)").isFalse();
        }
    }

    // ---------------------------------------------------------------------------------------------------- File

    @Test
    @DisplayName("File: one file with the order lands in the partner's directory")
    void fileTargetJourney() throws Exception {
        String base = FILE_DIR.toString().replace((char) 92, '/');
        Run run = journey("file", "FILE", "{\"directory\":\"" + base + "/{tenant}\"}", "file://{tenant}", true,
                tenantId -> {
                    try {
                        Files.createDirectories(FILE_DIR.resolve(tenantId.toString())); // <base>/<tenantId> (FileRoots)
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        Path directory = FILE_DIR.resolve(run.tenant().id().toString());
        assertJourneyReads(run);

        try (Stream<Path> files = Files.list(directory)) {
            List<Path> landed = files.filter(Files::isRegularFile).toList();
            assertThat(landed).as("exactly one file landed (no duplicate)").hasSize(1);
            assertThat(Files.readString(landed.get(0), StandardCharsets.UTF_8)).contains("ORD-FILE-1")
                    .contains("CUST-FILE");
        }
    }

    // ------------------------------------------------------------------------------------------------ readers

    /** Every message currently on the partner's queue (consumed), via a plain JMS client. */
    private static List<String> drainQueue(String queue) throws Exception {
        List<String> bodies = new ArrayList<>();
        try (jakarta.jms.Connection connection = new org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory(
                "tcp://127.0.0.1:" + ARTEMIS.getMappedPort(ARTEMIS_PORT)).createConnection()) {
            connection.start();
            jakarta.jms.Session session = connection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE);
            jakarta.jms.MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            long wait = 8000;
            jakarta.jms.Message message;
            while ((message = consumer.receive(wait)) != null) {
                if (message instanceof jakarta.jms.BytesMessage bytes) {
                    byte[] body = new byte[(int) bytes.getBodyLength()];
                    bytes.readBytes(body);
                    bodies.add(new String(body, StandardCharsets.UTF_8));
                } else if (message instanceof jakarta.jms.TextMessage text) {
                    bodies.add(text.getText());
                }
                wait = 2000;
            }
        }
        return bodies;
    }

    private static List<ConsumerRecord<String, String>> readTopic(String topic, int atLeast) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TARGET_KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "e18-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                if (records.size() >= atLeast && System.nanoTime() > deadline - Duration.ofSeconds(15).toNanos()) {
                    break; // a short extra look after the first record, to see a duplicate
                }
            }
        }
        return records;
    }

    /** The traceparent header of every work-topic record naming one of the ids, read from the start. */
    private static Map<String, List<String>> traceparentHeaders(List<String> ids) {
        Map<String, List<String>> headers = new java.util.HashMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "e18-trace-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of("platform.message.accepted", "platform.delivery.due"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && !ids.stream().allMatch(headers::containsKey)) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    String value = new String(record.value(), StandardCharsets.UTF_8);
                    org.apache.kafka.common.header.Header header = record.headers().lastHeader("traceparent");
                    ids.stream().filter(value::contains).forEach(id -> headers.computeIfAbsent(id,
                            any -> new ArrayList<>()).add(header == null ? "<none>"
                                    : new String(header.value(), StandardCharsets.UTF_8)));
                }
            }
        }
        return headers;
    }

}
