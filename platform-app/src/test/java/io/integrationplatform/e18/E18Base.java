package io.integrationplatform.e18;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import io.integrationplatform.tenancy.TenantContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * E18 (tracker F19, the project-close gate): the ONE running platform every E18 scenario shares - PostgreSQL, Keycloak,
 * the Kafka backbone and the whole application in-process (the E11 pattern), plus one real container per target type:
 * an API partner ({@link E18TargetStub}), an Artemis queue manager (MQ-CSV), a second Kafka broker (the partner's
 * own topic), a second PostgreSQL (the partner's database) and a directory (File).
 *
 * <p>One context for all three E18 classes (same configuration, inherited), so the memory budget measures the whole
 * suite's JVM. Every container starts once per JVM from the dynamic-property hook (Ryuk reaps them).
 *
 * <p>Test network: the partner names are {@code *.e18.test}. The destination guard is NOT relaxed beyond its own
 * showcase exception (T138/B21): the {@code showcase} profile lets these EXACT host names resolve to loopback on
 * these EXACT ports only. The adapter and route-template gates are switched on by {@code -De18.gates=kafka,db,file}
 * (default {@code kafka,db,file}: all three adapters and their route templates are on main) because switching a gate on without its beans stops the whole context from starting.
 */
@Tag("container")
@Tag("e2e-full")
@ExtendWith(E18Outcomes.class)
@ActiveProfiles({"showcase", "test"}) // 'test' unlocks DbTlsPolicy's disable mode: the partner Postgres has no TLS (R1 seam)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E18Base.TestNetwork.class)
abstract class E18Base {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Duration WAIT = Duration.ofSeconds(90);

    /** The pinned Artemis digest (platform-routing ArtemisContainers / platform-e2e E2eImages use the same). */
    static final String ARTEMIS_IMAGE = "apache/activemq-artemis:2.44.0"
            + "@sha256:0305c26f19eda00c0506d0953cfe265600a72ac1b17f7934412120c01a595cff";
    static final int ARTEMIS_PORT = 61616;

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            "http://localhost/login/oauth2/code/keycloak", "http://localhost/");
    static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    /** The MQ-CSV partner's queue manager. Anonymous login: the credential vault is not under test here. */
    static final GenericContainer<?> ARTEMIS = new GenericContainer<>(DockerImageName.parse(ARTEMIS_IMAGE))
            .withEnv("ANONYMOUS_LOGIN", "true").withEnv("TZ", "Etc/UTC").withExposedPorts(ARTEMIS_PORT)
            .waitingFor(Wait.forLogMessage(".*AMQ221007.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
    /** The Kafka-topic partner's OWN broker (never the platform's backbone, which a target may not point at). */
    static final KafkaContainer TARGET_KAFKA = new KafkaContainer(TestImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true");
    /** The DB partner's database; the approved table is created by the suite. */
    static final PostgreSQLContainer TARGET_DB = new PostgreSQLContainer(TestImages.POSTGRES)
            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust"); // the partner account's password lives in the vault, not under test
    static final E18TargetStub PARTNER = new E18TargetStub();
    static final Path FILE_DIR;

    static RestrictedLogin login;
    private static boolean grantedOnce;

    static {
        try {
            FILE_DIR = Files.createTempDirectory("e18-file-target");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ReadTenant readTenant;

    /** One throwaway 32-byte key for the JVM (base64), fixed so every property read sees the same value. */
    private static final String KEK_K1 = java.util.Base64.getEncoder().encodeToString(
            new java.security.SecureRandom().generateSeed(32));

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start();
        KAFKA.start();
        ARTEMIS.start();
        TARGET_KAFKA.start();
        TARGET_DB.start();
        createTopics(KAFKA.getBootstrapServers(), "platform.message.accepted", "platform.delivery.due",
                "platform.poison");
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_URL", () -> "/");
        // the credential API (dbTargetJourney stores the partner account secret) needs an active key-encryption key; a throwaway one per JVM
        registry.add("PLATFORM_CREDENTIAL_KEK_K1", () -> KEK_K1);
        registry.add("PLATFORM_CREDENTIAL_KEK_ACTIVE", () -> "K1");
        registry.add("IPASS_KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("IPASS_KAFKA_PUBLISH_CLIENT_ID", () -> "e18-relay");
        // T181 measurement dials (unset = today's behaviour: one loop per stage, the yml pool)
        for (String stage : new String[] {"stage1", "stage2"}) {
            String loops = System.getProperty("e18.loops");
            if (loops != null) {
                registry.add("platform.routing." + stage + ".concurrency", () -> loops);
            }
        }
        if (System.getProperty("e18.pool") != null) {
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> System.getProperty("e18.pool"));
        }
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.e18.test,localhost");
        registry.add("platform.config.destinations.showcase-local-hosts",
                () -> "api.e18.test,mq.e18.test,kafka.e18.test,db.e18.test,localhost");
        registry.add("platform.config.destinations.showcase-local-ports", () -> PARTNER.port() + ","
                + ARTEMIS.getMappedPort(ARTEMIS_PORT) + "," + TARGET_KAFKA.getMappedPort(9092) + ","
                + TARGET_DB.getMappedPort(5432));
        // harness only: the endpoint rows carry mq://, kafka://, jdbc: and file:// base URLs; the code fixes no scheme per type
        // (dispatch is by system.type, the scheme is only guarded), so the test environment approves exactly these
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https,mq,kafka,jdbc,file");
        // test environment only (R1): the Testcontainers brokers are clear text; production keeps the default (refuse)
        registry.add("platform.config.destinations.kafka-allow-plaintext", () -> "true");
        // test environment only: the partner database has no TLS and listens on a mapped port; FILE targets live under
        // <base>/<tenantId> (FileRoots) with the temp directory as the one approved base
        registry.add("platform.config.destinations.db-sslmode", () -> "disable");
        registry.add("platform.config.destinations.db-allowed-ports", () -> String.valueOf(TARGET_DB.getMappedPort(5432)));
        registry.add("platform.config.destinations.file-base-dirs", () -> FILE_DIR.toString().replace((char) 92, '/'));
        // adapter and route-template gates (all on main since 8bec282)
        String gates = System.getProperty("e18.gates", "kafka,db,file");
        if (gates.contains("kafka")) {
            registry.add("IPASS_KAFKA_TOPIC_TARGET_ENABLED", () -> "true");
            registry.add("IPASS_KAFKA_PLATFORM_TOPICS",
                    () -> "platform.message.accepted,platform.delivery.due,platform.poison");
        }
        if (gates.contains("db")) {
            registry.add("IPASS_DB_TARGET_ENABLED", () -> "true");
        }
        if (gates.contains("file")) {
            registry.add("IPASS_FILE_TARGET_ENABLED", () -> "true");
        }
    }

    // ------------------------------------------------------------------------------------------- test network

    /** {@code *.e18.test} resolves to loopback; the guard's showcase exception decides whether that is allowed. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestNetwork {

        @Bean
        @Primary
        HostResolver e18PartnerNames() {
            return host -> {
                if (host.endsWith(".e18.test") || host.equals("localhost")) {
                    return List.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
                }
                throw new UnknownHostException(host);
            };
        }

        /**
         * The restricted login joins ipaas_app strictly after Flyway and before the relay loops and the scheduler start
         * (a deployment grants the role before the app starts). Without it EXECUTE on ipaas_active_tenants() and the scheduler
         * schema are denied (SQLSTATE 42501 shown as BadSqlGrammar). Spring Boot applies every Callback bean to Flyway.
         */
        @Bean
        org.flywaydb.core.api.callback.Callback e18GrantAppRoleAfterMigrate() {
            return new GrantAppRole();
        }

        /** Not primary: the application's composite tenant context lists every other TenantContext as a source. */
        @Bean
        ReadTenant e18ReadTenant() {
            return new ReadTenant();
        }
    }

    /** AFTER_MIGRATE: grants ipaas_app to the runtime login on the owner connection; idempotent. */
    static final class GrantAppRole implements org.flywaydb.core.api.callback.Callback {
        @Override
        public boolean supports(org.flywaydb.core.api.callback.Event event, org.flywaydb.core.api.callback.Context context) {
            return event == org.flywaydb.core.api.callback.Event.AFTER_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(org.flywaydb.core.api.callback.Event event, org.flywaydb.core.api.callback.Context context) {
            return false;
        }

        @Override
        public void handle(org.flywaydb.core.api.callback.Event event, org.flywaydb.core.api.callback.Context context) {
            try {
                PlatformTestWiring.grantApplicationRole(POSTGRES, login);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("the app login could not join ipaas_app after migration", e);
            }
        }

        @Override
        public String getCallbackName() {
            return "e18GrantAppRoleAfterMigrate";
        }
    }

    /** An extra tenant source for the test thread: lets the suite call the tracking and report beans as a tenant. */
    static final class ReadTenant implements TenantContext {

        private final ThreadLocal<UUID> bound = new ThreadLocal<>();

        <T> T as(UUID tenantId, Supplier<T> read) {
            bound.set(tenantId);
            try {
                return read.get();
            } finally {
                bound.remove();
            }
        }

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(bound.get());
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static void createTopics(String bootstrapServers, String... topics) {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers))) {
            admin.createTopics(java.util.Arrays.stream(topics).map(topic -> new NewTopic(topic, io.integrationplatform.routing.topics.Topics.WORK_TOPIC_PARTITIONS, (short) 1))
                    .toList()).all().get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new IllegalStateException("topics could not be created: " + e.getMessage(), e);
            }
        }
    }

    URI backend() {
        return URI.create("http://localhost:" + port);
    }

    /** A new source credential (client credentials) for one tenant. */
    ClientCredentials newSource(String prefix) {
        return KEYCLOAK.createConfidentialClient(prefix + "-" + UUID.randomUUID().toString().substring(0, 8), true);
    }

    /** Posts one message to the common intake API; returns the tracking id the 202 answer carries. */
    UUID post(ClientCredentials source, String interfaceCode, String body, String traceparent) throws Exception {
        String token = KEYCLOAK.clientCredentialsToken(source.clientId(), source.secret());
        HttpRequest.Builder request = HttpRequest.newBuilder(
                backend().resolve("/api/v1/interfaces/" + interfaceCode + "/messages"));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        HttpResponse<String> answer = HTTP.send(request.timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", E18Seed.SOURCE_CONTRACT_LABEL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("the common intake API accepts: %s", answer.body()).isEqualTo(202);
        return UUID.fromString(JSON.readTree(answer.body()).path("trackingId").asString());
    }

    static void await(String link, BooleanSupplier condition) {
        await(link, WAIT, condition);
    }

    static void await(String link, Duration limit, BooleanSupplier condition) {
        long deadline = System.nanoTime() + limit.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + link, e);
        }
        throw new AssertionError("waited " + limit.toSeconds() + " s for " + link + " - it never happened");
    }

    /** The pipeline links in order; each wait names the link it waits on so a red run says which is missing. */
    static void awaitSettled(UUID messageId, int deliveries) {
        await("the outbox relay to publish message " + messageId + " (intake -> outbox -> relay -> Kafka)",
                () -> ownerLong("select count(*) from public.outbox where aggregate_id = ?::uuid"
                        + " and topic = 'platform.message.accepted' and published_at is not null", messageId) == 1);
        await("stage 1 to route message " + messageId + " into " + deliveries + " delivery row(s)",
                () -> "ROUTED".equals(ownerString("select state from public.message where id = ?::uuid", messageId))
                        && ownerLong("select count(*) from public.delivery where message_id = ?::uuid",
                                messageId) == deliveries);
        await("stage 2 to settle every delivery of message " + messageId + " (DELIVERED or DEAD_LETTERED)",
                () -> ownerLong("select count(*) from public.delivery where message_id = ?::uuid"
                        + " and state in ('DELIVERED', 'DEAD_LETTERED')", messageId) == deliveries);
    }

    static String ownerString(String sql, Object... args) {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement read = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setString(i + 1, String.valueOf(args[i]));
            }
            try (ResultSet rows = read.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("owner read failed: " + e.getMessage(), e);
        }
    }

    static long ownerLong(String sql, Object... args) {
        String value = ownerString(sql, args);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** The delivery rows of one message: {@code id -> state}. */
    static Map<UUID, String> deliveryStates(UUID messageId) {
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
        } catch (Exception e) {
            throw new IllegalStateException("owner read failed: " + e.getMessage(), e);
        }
    }

    /** The last error text a delivery's attempts recorded - what a red run quotes as its reason. */
    static String lastAttemptDetail(UUID messageId) {
        String detail = ownerString("select a.detail from public.delivery_attempt a join public.delivery d on"
                + " d.id = a.delivery_id where d.message_id = ?::uuid order by a.finished_at desc nulls last limit 1",
                messageId);
        return detail == null ? "<no attempt row>" : detail;
    }

    @FunctionalInterface
    interface Sql {
        void run(Connection owner) throws Exception;
    }

    static void asOwner(Sql work) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            work.run(owner);
        }
    }

    static synchronized void grantAppRoleOnce() throws Exception {
        if (!grantedOnce) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
            grantedOnce = true;
        }
    }
}
