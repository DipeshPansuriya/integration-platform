package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions;
import io.integrationplatform.e11.DipeshExampleSeed;
import io.integrationplatform.identity.KeycloakRealmContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T141 / T-147 Release 1 security gate, suite S7 (TenantIsolation), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityGateS7TenantIsolationContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    /** The worker-style tenant binding the application's composite TenantContext composes (as a stage consumer's). */
    @TestConfiguration
    static class BindingConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        GateFixture.BoundTenant gateBoundTenant(
                @org.springframework.beans.factory.annotation.Qualifier("requestTenantContext")
                io.integrationplatform.tenancy.TenantContext request) {
            return new GateFixture.BoundTenant(request);
        }
    }

    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private GateFixture.BoundTenant binding;
    @Autowired
    private Stage1Deliveries stage1;
    @Autowired
    private DeliveryOperatorActions actions;

    private GateFixture fixture() throws SQLException {
        GateFixture fixture = new GateFixture(POSTGRES, login, binding, transactions);
        fixture.seedTenants();
        return fixture;
    }

    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Source systems call the intake with client credentials from this realm (S7-A3 only). */
    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            "http://localhost/login/oauth2/code/keycloak", "http://localhost/");

    @LocalServerPort
    private int port;

    private static final AtomicBoolean GRANTED = new AtomicBoolean();
    private static PlatformTestWiring.RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start(); // idempotent; the issuer must be known before the context starts
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_URL", () -> "/");
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.e11.test");
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    @BeforeEach
    void grantApplicationRole() throws SQLException {
        if (GRANTED.compareAndSet(false, true)) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), login.username(), login.password());
    }

    private static TreeSet<String> query(String sql) throws SQLException {
        TreeSet<String> result = new TreeSet<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    /**
     * S7-A1: Unique (message_id, route_id, created_at) under parallel double-submit.
     * Expected: ON CONFLICT DO NOTHING → exactly 1 delivery, 1 outbox row; the loser gets 0 rows and writes nothing
     */
    @Test
    void s7_a1_unique_under_parallel_double_submit() throws Exception {
        GateFixture f = fixture();
        Instant now = Instant.now();
        for (int round = 0; round < 5; round++) {
            UUID message = f.insertMessage(GateFixture.TENANT_A, now, "s7a1-" + round);
            List<io.integrationplatform.delivery.port.Stage1Delivery> routes = List.of(
                    GateFixture.delivery(message, UUID.randomUUID(), now),
                    GateFixture.delivery(message, UUID.randomUUID(), now),
                    GateFixture.delivery(message, UUID.randomUUID(), now));
            // the same record handled by two consumers at once: exactly one of them inserts each route
            List<Integer> inserted = concurrently(2,
                    () -> f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(routes)));
            assertThat(inserted.stream().mapToInt(Integer::intValue).sum()).as("round %d: %s", round, inserted)
                    .isEqualTo(3);
            assertThat(f.scalar(GateFixture.TENANT_A, "select count(*) from public.delivery where message_id ="
                    + " ?::uuid", message.toString())).isEqualTo("3");
            assertThat(f.ownerScalar("select count(*) from public.outbox o join public.delivery d on d.id ="
                    + " o.aggregate_id where d.message_id = ?::uuid and o.topic = 'platform.delivery.due'",
                    message.toString())).as("one outbox row per delivery").isEqualTo("3");
        }
    }

    /**
     * S7-A2: Replay re-drive creates no second delivery (ADR-014: replay re-drives the same row).
     * Expected: Same row DEAD_LETTERED → DUE, never a second delivery row; created_at still the message's accepted day
     */
    @Test
    void s7_a2_replay_re_drive_creates_no_second() throws Exception {
        GateFixture f = fixture();
        Instant acceptedAt = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(12, ChronoUnit.HOURS);
        UUID message = f.insertMessage(GateFixture.TENANT_A, acceptedAt, "s7a2");
        f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(
                List.of(GateFixture.delivery(message, UUID.randomUUID(), acceptedAt))));
        UUID delivery = UUID.fromString(f.scalar(GateFixture.TENANT_A,
                "select id::text from public.delivery where message_id = ?::uuid", message.toString()));
        // the delivery has been pinned and dead-lettered by its run (owner stands in for the earlier attempts)
        f.ownerExecute("update public.delivery set contract_version_id = gen_random_uuid(), state = 'DEAD_LETTERED',"
                + " dead_letter_class = 'PERMANENT' where id = ?::uuid", delivery.toString());
        String createdBefore = f.ownerScalar("select created_at::text from public.delivery where id = ?::uuid",
                delivery.toString());
        // another tenant's operator cannot even see it
        assertThat(binding.callAs(GateFixture.TENANT_B, () -> actions.replay(delivery, "gate-b")).outcome())
                .isEqualTo(DeliveryOperatorActions.Outcome.NOT_FOUND);
        assertThat(f.ownerScalar("select state from public.delivery where id = ?::uuid", delivery.toString()))
                .isEqualTo("DEAD_LETTERED");
        // the owner's replay re-drives the same row
        assertThat(binding.callAs(GateFixture.TENANT_A, () -> actions.replay(delivery, "gate-a")).outcome())
                .isEqualTo(DeliveryOperatorActions.Outcome.MOVED_TO_DUE);
        assertThat(f.ownerScalar("select state from public.delivery where id = ?::uuid", delivery.toString()))
                .isEqualTo("DUE");
        // a repeated replay is refused as the wrong state and creates nothing
        assertThat(binding.callAs(GateFixture.TENANT_A, () -> actions.replay(delivery, "gate-a")).outcome())
                .isEqualTo(DeliveryOperatorActions.Outcome.WRONG_STATE);
        assertThat(f.ownerScalar("select count(*) from public.delivery where message_id = ?::uuid", message.toString()))
                .as("never a second delivery row").isEqualTo("1");
        assertThat(f.ownerScalar("select created_at::text from public.delivery where id = ?::uuid",
                delivery.toString())).as("created_at is still the message's accepted day").isEqualTo(createdBefore);
    }

    /**
     * S7-A3: Cross-tenant message-id submission.
     * Expected: Ingest resolves tenant from the credential (ADR-007 D2); B's ids never match A's routing; 403 + audit on body customerId mismatch
     */
    @Test
    void s7_a3_cross_tenant_message_id_submission() throws Exception {
        GateFixture f = fixture();
        KeycloakRealmContainer.ClientCredentials sourceA = KEYCLOAK.createConfidentialClient(
                "s7a3-a-" + UUID.randomUUID().toString().substring(0, 8), true);
        KeycloakRealmContainer.ClientCredentials sourceB = KEYCLOAK.createConfidentialClient(
                "s7a3-b-" + UUID.randomUUID().toString().substring(0, 8), true);
        DipeshExampleSeed.Tenant a;
        DipeshExampleSeed.Tenant b;
        try (java.sql.Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            a = DipeshExampleSeed.onboard(owner, "alpha", "S7-CUST-A", sourceA.clientId(), KEYCLOAK.issuerUri());
            b = DipeshExampleSeed.onboard(owner, "bravo", "S7-CUST-B", sourceB.clientId(), KEYCLOAK.issuerUri());
        }
        String idempotencyKey = UUID.randomUUID().toString();
        // B submits one record; A submits its own with the SAME idempotency key (a "cross-tenant message id")
        HttpResponse<String> fromB = intake(sourceB, "{\"customerId\":\"S7-CUST-B\",\"orderId\":\"B-1\","
                + "\"amount\":1}", idempotencyKey);
        assertThat(fromB.statusCode()).as(fromB.body()).isEqualTo(202);
        UUID trackingB = UUID.fromString(JSON.readTree(fromB.body()).path("trackingId").asString());
        HttpResponse<String> fromA = intake(sourceA, "{\"customerId\":\"S7-CUST-A\",\"orderId\":\"A-1\","
                + "\"amount\":1}", idempotencyKey);
        assertThat(fromA.statusCode()).as(fromA.body()).isEqualTo(202);
        UUID trackingA = UUID.fromString(JSON.readTree(fromA.body()).path("trackingId").asString());
        assertThat(trackingA).as("B's id never answers A's submission").isNotEqualTo(trackingB);
        assertThat(f.ownerScalar("select tenant_id::text from public.message where id = ?::uuid", trackingA.toString()))
                .isEqualTo(a.id().toString());
        assertThat(f.ownerScalar("select tenant_id::text from public.message where id = ?::uuid", trackingB.toString()))
                .isEqualTo(b.id().toString());
        // the ingest resolves the tenant from the credential: A's token naming B's customerId is refused 403,
        // nothing is written for either tenant
        String messagesBefore = f.ownerScalar("select count(*) from public.message");
        HttpResponse<String> forged = intake(sourceA, "{\"customerId\":\"S7-CUST-B\",\"orderId\":\"X-1\","
                + "\"amount\":1}", UUID.randomUUID().toString());
        assertThat(forged.statusCode()).as(forged.body()).isEqualTo(403);
        assertThat(forged.body()).as("the refusal names no tenant B data").doesNotContain("S7-CUST-B")
                .doesNotContain(b.id().toString());
        assertThat(f.ownerScalar("select count(*) from public.message")).isEqualTo(messagesBefore);
        // and B's message is invisible to a transaction stamped as A
        assertThat(f.scalar(a.id(), "select count(*) from public.message where id = ?::uuid", trackingB.toString()))
                .isEqualTo("0");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> intake(KeycloakRealmContainer.ClientCredentials source, String body,
            String idempotencyKey) throws Exception {
        String token = KEYCLOAK.clientCredentialsToken(source.clientId(), source.secret());
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/interfaces/"
                        + DipeshExampleSeed.INTERFACE_CODE + "/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", DipeshExampleSeed.SOURCE_CONTRACT_LABEL)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * S7-A4: Payload retention / purge guard (30 days, m-f1fcfc; ADR-014 conformance 5).
     * Expected: Guard day survives (nothing dropped); clean day's message_D, payload_D, delivery_D all dropped; DELETE as ipaas_app fails 42501 on payload/message
     */
    @Test
    @Disabled("BLOCKED by T75: only PayloadPurgePlaceholderHandler is on main (platform-app scheduler); the real payload retention purge (partition drop, maintenance role T-135b) is not built (T141 gate case S7-A4)")
    void s7_a4_payload_retention_purge_guard() {
        fail("S7-A4 body lands with T75");
    }

    /**
     * S7-A5: Message state transitions resistant to replay.
     * Expected: No state machine regression; the conditional update only mutates ACCEPTED; ROUTE_FAILED follows its ADR-010 path
     */
    @Test
    void s7_a5_message_state_transitions_resistant_to_replay() throws Exception {
        GateFixture f = fixture();
        Instant now = Instant.now();
        // a duplicate record handled by two consumers at once: exactly one flip, ACCEPTED -> ROUTED
        UUID raced = f.insertMessage(GateFixture.TENANT_A, now, "s7a5-race");
        List<Boolean> flips = concurrently(2,
                () -> f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(raced)));
        assertThat(flips).containsExactlyInAnyOrder(true, false);
        assertThat(f.scalar(GateFixture.TENANT_A, "select state from public.message where id = ?::uuid",
                raced.toString())).isEqualTo("ROUTED");
        // replays of a ROUTED message change nothing
        String routedAt = f.scalar(GateFixture.TENANT_A, "select routed_at::text from public.message where id ="
                + " ?::uuid", raced.toString());
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(raced))).isFalse();
        assertThat(f.scalar(GateFixture.TENANT_A, "select routed_at::text from public.message where id = ?::uuid",
                raced.toString())).isEqualTo(routedAt);
        // a ROUTE_FAILED message is not resurrected by a replayed record (the guard only mutates ACCEPTED)
        UUID failed = f.insertMessage(GateFixture.TENANT_A, now, "s7a5-failed");
        f.asTenant(GateFixture.TENANT_A, c -> {
            try (java.sql.PreparedStatement st = c.prepareStatement("update public.message set state = 'ROUTE_FAILED',"
                    + " updated_at = now() where id = ?::uuid")) {
                st.setString(1, failed.toString());
                return st.executeUpdate();
            }
        });
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(failed))).isFalse();
        assertThat(f.scalar(GateFixture.TENANT_A, "select state from public.message where id = ?::uuid",
                failed.toString())).isEqualTo("ROUTE_FAILED");
        // the closed state set: a state outside it is refused by the table
        UUID other = f.insertMessage(GateFixture.TENANT_A, now, "s7a5-bogus");
        assertThatThrownBy(() -> f.asTenant(GateFixture.TENANT_A, c -> {
            try (java.sql.PreparedStatement st = c.prepareStatement("update public.message set state = 'BOGUS'"
                    + " where id = ?::uuid")) {
                st.setString(1, other.toString());
                return st.executeUpdate();
            }
        })).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }

    /**
     * S7-A6: message/payload grant conformance (ADR-014 §1).
     * Expected: All 42501; grants: message SELECT/INSERT/UPDATE(state, routed_at, updated_at), payload SELECT/INSERT only
     */
    @Test
    void s7_a6_message_payload_grant_conformance() throws SQLException {
        // every statement is tried as the restricted application login, tenant stamped, and must be refused 42501
        List<String> tamper = List.of(
                "update public.message set tenant_id = tenant_id",
                "update public.message set accepted_at = accepted_at",
                "delete from public.message",
                "truncate public.message",
                "update public.payload set tenant_id = tenant_id",
                "delete from public.payload",
                "truncate public.payload");
        try (Connection app = appConnection()) {
            app.setAutoCommit(false);
            for (String statement : tamper) {
                try (Statement sql = app.createStatement()) {
                    // a rolled-back transaction drops the transaction-local stamp: stamp again for every attempt
                    sql.execute("select set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true)");
                }
                assertThatThrownBy(() -> {
                    try (Statement sql = app.createStatement()) {
                        sql.execute(statement);
                    }
                }).as(statement).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(statement).isEqualTo("42501"));
                app.rollback();
            }
        }
        assertThat(query("select privilege_type from information_schema.role_table_grants where table_schema ="
                + " 'public' and table_name = 'message' and grantee = 'ipaas_app'"))
                .as("table privileges of the application role on message").containsExactly("INSERT", "SELECT");
        assertThat(query("select column_name from information_schema.column_privileges where table_schema = 'public'"
                + " and table_name = 'message' and grantee = 'ipaas_app' and privilege_type = 'UPDATE'"))
                .as("updatable columns of message").containsExactly("routed_at", "state", "updated_at");
        assertThat(query("select privilege_type from information_schema.role_table_grants where table_schema ="
                + " 'public' and table_name = 'payload' and grantee = 'ipaas_app'"))
                .as("table privileges of the application role on payload").containsExactly("INSERT", "SELECT");
        assertThat(query("select column_name from information_schema.column_privileges where table_schema = 'public'"
                + " and table_name = 'payload' and grantee = 'ipaas_app' and privilege_type = 'UPDATE'"))
                .as("updatable columns of payload").isEmpty();
        // a partition reached by its own name must not widen the parent's grants (ADR-014)
        List<String> widened = new ArrayList<>();
        for (String partition : query("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                + " join pg_class p on p.oid = i.inhparent where p.relname in ('message', 'payload')")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                if (!query("select has_table_privilege('ipaas_app', 'public.\"" + partition + "\"', '" + privilege
                        + "')::text").first().equals("false")) {
                    widened.add(partition + " grants " + privilege);
                }
            }
        }
        assertThat(widened).as("partitions that grant the application role more than the parent").isEmpty();
    }
}
