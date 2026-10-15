package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2AttemptWriter;
import io.integrationplatform.routing.stage2.Stage2OutcomeHandoff;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.camel.impl.DefaultCamelContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The API target adapter's real send (E10 narrow, T-050, ADR-015): {@code SocketHttpSend} over the
 * {@link DestinationGuard}-pinned address, through {@code JdbcTargetEndpointViews} reading the tenant's
 * {@code target_endpoint} row (V2_2_1) - on the real PostgreSQL of the message-store family only this module's
 * test classpath carries every migration. WireMock stands in for the target, reached the
 * {@code ApiAdapterWireMock} way (pinned TEST-NET-3 documentation address, dialled to the container port).
 *
 * <pre>
 * Scenario: the send arrives        - 201 answers normally, one request on the wire with the body and the key
 * Scenario: the target says "later" - 503 raises the TRANSIENT fault the layer-1 route redelivers on
 * Scenario: the target is slow      - the endpoint's timeout_ms cuts the send, classed AMBIGUOUS (may have gone)
 * Scenario: no endpoint row         - PERMANENT, nothing dialled
 * </pre>
 *
 * <p>T-051 fault matrix (row 278, T95): each WireMock fault runs through the layer-1 route with the real adapter,
 * the real attempt ledger and the recorder path, and the Delivery row lands in the state ADR-010 section 5
 * prescribes for the class {@code OutcomeClassifier} gives it:
 *
 * <pre>
 * 429                       - TRANSIENT: redelivered inside the attempt (3 calls), then RETRY_WAIT
 * 401                       - PERMANENT: one call, DEAD_LETTERED (PERMANENT), never retried
 * 500 (no idempotent recv.) - AMBIGUOUS: one call, DEAD_LETTERED (AMBIGUOUS) for an operator decision
 * timeout                   - AMBIGUOUS, never cleanly failed: DEAD_LETTERED (AMBIGUOUS)
 * timeout, idempotent recv. - TRANSIENT: redelivered, then RETRY_WAIT
 * refused destination       - PERMANENT: DEAD_LETTERED (PERMANENT), WireMock received nothing
 * connection reset          - TRANSIENT (backlog E10-F1-S1): redelivered, then RETRY_WAIT
 * per-target timeout        - the endpoint's own timeout_ms decides, not a shared default
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class ApiTargetCallSocketHttpSendContainerTest {

    private static final UUID TENANT = UUID.fromString("eeee0000-0000-0000-0000-00000000000a");
    private static final String PATH = "/orders";
    private static final UUID SYSTEM = UUID.fromString("eeee0000-0000-0000-0000-00000000f001");
    /** Same target, but it declares idempotent receive (ADR-011): a timeout is TRANSIENT, not AMBIGUOUS. */
    private static final UUID IDEMPOTENT_ENDPOINT = UUID.fromString("eeee0000-0000-0000-0000-00000000f003");
    /** Same target with a longer timeout_ms of its own: the per-target timeout, not a shared default. */
    private static final UUID PATIENT_ENDPOINT = UUID.fromString("eeee0000-0000-0000-0000-00000000f004");
    /** A host that is not on the allow-list: the guard refuses it before any address is dialled. */
    private static final UUID REFUSED_ENDPOINT = UUID.fromString("eeee0000-0000-0000-0000-00000000f005");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");
    /** Not a Testcontainers {@code Startable}: started in {@code migrateAndSeed}, stopped in {@code tearDown}. */
    static final ApiAdapterWireMock WIREMOCK = new ApiAdapterWireMock();

    private DataSource owner;
    private UUID endpointId;
    private ApiAdapterWireMock wiremock;
    private io.integrationplatform.config.destination.SocketDialer dialer;
    private io.integrationplatform.config.destination.HostResolver resolver;
    private final AtomicInteger idempotency = new AtomicInteger();
    private Instant deliveryCreatedAt;

    @BeforeAll
    void migrateAndSeed() throws SQLException {
        org.postgresql.Driver.class.getName();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        wiremock = WIREMOCK.start();
        dialer = wiremock.dialer();
        resolver = wiremock.resolver();
        try (Connection sql = owner.getConnection()) {
            PreparedStatement tenant = sql.prepareStatement(
                    "insert into public.tenant (id, code, status, time_zone) values (?, 'e10-a', 'ACTIVE', 'Etc/UTC')");
            tenant.setObject(1, TENANT);
            tenant.executeUpdate();
            PreparedStatement system = sql.prepareStatement(
                    "insert into public.system (id, tenant_id, code, name, direction) values (?, ?, 'tgt',"
                            + " 'Orders target', 'TARGET')");
            system.setObject(1, UUID.fromString("eeee0000-0000-0000-0000-00000000f001"));
            system.setObject(2, TENANT);
            system.executeUpdate();
            endpointId = UUID.fromString("eeee0000-0000-0000-0000-00000000f002");
            PreparedStatement endpoint = sql.prepareStatement(
                    "insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                            + " http_method, auth_method, timeout_ms) values (?, ?, ?, 'orders-api',"
                            + " 'Orders target API', ?, 'POST', 'NO_AUTH', ?)");
            endpoint.setObject(1, endpointId);
            endpoint.setObject(2, TENANT);
            endpoint.setObject(3, UUID.fromString("eeee0000-0000-0000-0000-00000000f001"));
            endpoint.setString(4, ApiAdapterWireMock.address(PATH));
            endpoint.setInt(5, 1_500);
            endpoint.executeUpdate();
            insertEndpoint(sql, IDEMPOTENT_ENDPOINT, "orders-api-idem", ApiAdapterWireMock.address(PATH), 1_500, true);
            insertEndpoint(sql, PATIENT_ENDPOINT, "orders-api-patient", ApiAdapterWireMock.address(PATH), 6_000,
                    false);
            insertEndpoint(sql, REFUSED_ENDPOINT, "orders-api-refused", "http://refused.e10.test:8080" + PATH, 1_500,
                    false);
        }
        // noon of the database's own CURRENT_DATE: V2_3_1 makes delivery partitions only around that day
        deliveryCreatedAt = JdbcClient.create(owner).sql("select current_date::timestamptz + interval '12 hours'")
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static void insertEndpoint(Connection sql, UUID id, String code, String baseUrl, int timeoutMs,
            boolean idempotentReceive) throws SQLException {
        PreparedStatement endpoint = sql.prepareStatement(
                "insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url, http_method,"
                        + " auth_method, timeout_ms, idempotent_receive) values (?, ?, ?, ?, 'Orders target API', ?,"
                        + " 'POST', 'NO_AUTH', ?, ?)");
        endpoint.setObject(1, id);
        endpoint.setObject(2, TENANT);
        endpoint.setObject(3, SYSTEM);
        endpoint.setString(4, code);
        endpoint.setString(5, baseUrl);
        endpoint.setInt(6, timeoutMs);
        endpoint.setBoolean(7, idempotentReceive);
        endpoint.executeUpdate();
    }

    @AfterAll
    void tearDown() {
        wiremock.stop();
    }

    @Test
    @DisplayName("the send arrives: 201 answers normally and the body with the key hit the wire once")
    void sendArrives() {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 201, "");
        calls().sendInTenantTransaction(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "CUST-1", endpointId,
                false));

        assertThat(wiremock.requestCount()).isEqualTo(1);
        assertThat(wiremock.requestBodies()).hasSize(1);
        assertThat(wiremock.requestBodies().get(0)).isEqualTo("{\"x\":1}");
    }

    @Test
    @DisplayName("the target says \"later\": 503 raises the TRANSIENT fault the layer-1 route redelivers on")
    void serviceUnavailableIsTransient() {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 503, "");
        assertThatThrownBy(() -> calls().sendInTenantTransaction(stage2Send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    @DisplayName("the target is slow: the endpoint's timeout_ms cuts the send, classed AMBIGUOUS")
    void slowTargetTimesOutAmbiguous() {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 200, "\"fixedDelayMilliseconds\":3000");
        assertThatThrownBy(() -> calls().sendInTenantTransaction(stage2Send()))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass().name()).isEqualTo("AMBIGUOUS"));
    }

    @Test
    @DisplayName("no endpoint row: PERMANENT, nothing dialled")
    void missingEndpointIsPermanent() {
        wiremock.reset();
        assertThatThrownBy(() -> calls().sendInTenantTransaction(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(),
                        "CUST-1", UUID.fromString("eeee0000-0000-0000-0000-00000000f0ff"), false)))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass().name()).isEqualTo("PERMANENT"));
        assertThat(wiremock.requestCount()).as("nothing dialled for an unknown endpoint").isZero();
    }

    @Test
    @DisplayName("T-051 429: TRANSIENT - redelivered inside the attempt (3 calls), the Delivery lands in RETRY_WAIT")
    void tooManyRequestsLandsInRetryWait() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 429, "");
        Landed landed = land(endpointId, false);

        assertThat(wiremock.requestCount()).as("first try + 2 layer-1 redeliveries").isEqualTo(3);
        assertThat(landed.attempts()).containsExactly("1:TRANSIENT", "1:TRANSIENT", "1:TRANSIENT");
        assertThat(landed.state()).isEqualTo("RETRY_WAIT");
        assertThat(landed.deadLetterClass()).isNull();
    }

    @Test
    @DisplayName("T-051 401: PERMANENT - one call, the Delivery is DEAD_LETTERED (PERMANENT), never retried")
    void unauthorizedIsDeadLetteredPermanent() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 401, "");
        Landed landed = land(endpointId, false);

        assertThat(wiremock.requestCount()).as("a refusal is never redelivered").isEqualTo(1);
        assertThat(landed.attempts()).containsExactly("1:PERMANENT");
        assertThat(landed.state()).isEqualTo("DEAD_LETTERED");
        assertThat(landed.deadLetterClass()).isEqualTo("PERMANENT");
    }

    @Test
    @DisplayName("T-051 500 (no idempotent receive): AMBIGUOUS - one call, DEAD_LETTERED (AMBIGUOUS) for an operator")
    void serverErrorIsAmbiguousDeadLetter() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 500, "");
        Landed landed = land(endpointId, false);

        assertThat(wiremock.requestCount()).as("an ambiguous outcome is never redelivered").isEqualTo(1);
        assertThat(landed.attempts()).containsExactly("1:AMBIGUOUS");
        assertThat(landed.state()).isEqualTo("DEAD_LETTERED");
        assertThat(landed.deadLetterClass()).isEqualTo("AMBIGUOUS");
    }

    @Test
    @DisplayName("T-051 timeout: AMBIGUOUS, never cleanly failed - DEAD_LETTERED (AMBIGUOUS), not PERMANENT")
    void timeoutIsAmbiguousNeverCleanlyFailed() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 200, "\"fixedDelayMilliseconds\":3000");
        Landed landed = land(endpointId, false);

        assertThat(landed.attempts()).as("one try, never redelivered").containsExactly("1:AMBIGUOUS");
        assertThat(landed.state()).isEqualTo("DEAD_LETTERED");
        assertThat(landed.deadLetterClass()).as("the request may have been processed").isEqualTo("AMBIGUOUS");
    }

    @Test
    @DisplayName("T-051 timeout with idempotent receive: TRANSIENT - redelivered, the Delivery lands in RETRY_WAIT")
    void timeoutAgainstIdempotentTargetIsTransient() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 200, "\"fixedDelayMilliseconds\":3000");
        Landed landed = land(IDEMPOTENT_ENDPOINT, true);

        assertThat(landed.attempts()).containsExactly("1:TRANSIENT", "1:TRANSIENT", "1:TRANSIENT");
        assertThat(landed.state()).isEqualTo("RETRY_WAIT");
        assertThat(landed.deadLetterClass()).isNull();
    }

    @Test
    @DisplayName("T-051 refused destination: PERMANENT, DEAD_LETTERED, and WireMock received 0 requests")
    void refusedDestinationIsPermanentAndNothingSent() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 201, "");
        Landed landed = land(REFUSED_ENDPOINT, false);

        assertThat(wiremock.requestCount()).as("nothing is sent to a refused destination").isZero();
        assertThat(landed.attempts()).containsExactly("1:PERMANENT");
        assertThat(landed.state()).isEqualTo("DEAD_LETTERED");
        assertThat(landed.deadLetterClass()).isEqualTo("PERMANENT");
    }

    @Test
    @DisplayName("T-051 connection reset: TRANSIENT - redelivered inside the attempt (3 calls), the Delivery lands in"
            + " RETRY_WAIT")
    void connectionResetLandsInRetryWait() throws Exception {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 200, "\"fault\":\"CONNECTION_RESET_BY_PEER\"");
        Landed landed = land(endpointId, false);

        assertThat(wiremock.requestCount()).as("first try + 2 layer-1 redeliveries").isEqualTo(3);
        assertThat(landed.attempts()).containsExactly("1:TRANSIENT", "1:TRANSIENT", "1:TRANSIENT");
        assertThat(landed.state()).isEqualTo("RETRY_WAIT");
        assertThat(landed.deadLetterClass()).isNull();
    }

    @Test
    @DisplayName("T-051 per-target timeout: a 2.5 s answer arrives at a 6000 ms endpoint, is cut at a 1500 ms one")
    void perTargetTimeoutIsHonoured() {
        wiremock.reset();
        wiremock.stubAny("POST", PATH, 201, "\"fixedDelayMilliseconds\":2500");

        calls().sendInTenantTransaction(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "CUST-1",
                PATIENT_ENDPOINT, false));
        assertThatThrownBy(() -> calls().sendInTenantTransaction(stage2Send()))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass().name()).isEqualTo("AMBIGUOUS"));
    }

    /** Where one fault left the Delivery: its state, its dead-letter class and the layer-1 attempt rows in order. */
    private record Landed(String state, String deadLetterClass, List<String> attempts) {
    }

    /**
     * One delivery through the production layer-1 path: created and claimed (PENDING -> SENDING), sent through the
     * layer-1 route with the real adapter, the real attempt ledger and the recorder path, then read back - every
     * statement in a tenant-stamped transaction (R11).
     */
    private Landed land(UUID endpoint, boolean targetDeclaresIdempotentReceive) throws Exception {
        UUID delivery = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        JdbcClient jdbc = JdbcClient.create(owner);
        JdbcDeliveryRepository repository = new JdbcDeliveryRepository(jdbc, () -> Optional.of(TENANT));
        stamped().executeWithoutResult(status -> {
            repository.create(delivery, UUID.fromString("eeee0000-0000-0000-0000-0000000000a1"), "t051-" + delivery,
                    null, endpoint, deliveryCreatedAt);
            assertThat(repository.claimSend(delivery, claim, deliveryCreatedAt)).as("claimed").isTrue();
        });

        TargetCall target = send -> stamped().executeWithoutResult(status -> calls().target().call(send));
        Stage2AttemptWriter attempts = (id, layer, outcome, startedAt, finishedAt, detail) ->
                stamped().executeWithoutResult(status ->
                        repository.recordAttempt(id, layer, outcome, startedAt, finishedAt, detail));
        OutcomeRecorder recorder = new OutcomeRecorder(new JdbcDeliveryRetryStore(jdbc, () -> Optional.of(TENANT)),
                RetryPolicy.defaults(), () -> 0.0);
        Stage2OutcomeHandoff handoff = (id, token, tries, outcome, idempotent) ->
                stamped().executeWithoutResult(status ->
                        recorder.record(id, token, tries, outcome, Instant.now(), null, idempotent));
        DefaultCamelContext context = new DefaultCamelContext();
        try {
            context.addRoutes(new LayerOneRouteBuilder(target, attempts, handoff, LayerOneWaits.defaults()));
            context.start();
            context.createProducerTemplate().sendBody(LayerOneRouteBuilder.LAYER_ONE_SEND,
                    new Stage2Send(delivery, claim, "CUST-1", endpoint, targetDeclaresIdempotentReceive));
        } finally {
            context.stop();
        }

        return stamped().execute(status -> {
            Map<String, Object> row = jdbc.sql("select state, dead_letter_class from public.delivery where id = ?")
                    .param(delivery).query().singleRow();
            List<String> rows = jdbc.sql("select layer || ':' || outcome_class from public.delivery_attempt"
                            + " where delivery_id = ? order by id")
                    .param(delivery).query(String.class).list();
            return new Landed((String) row.get("state"), (String) row.get("dead_letter_class"), rows);
        });
    }

    /** A transaction that stamps {@code app.tenant_id} at its start, the production shape (R11). */
    private TransactionTemplate stamped() {
        JdbcTransactionManager manager = new JdbcTransactionManager(owner) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(connection, definition);
                try (PreparedStatement stamp = connection.prepareStatement(
                        "select set_config('app.tenant_id', ?, true)")) {
                    stamp.setString(1, TENANT.toString());
                    stamp.execute();
                }
            }
        };
        return new TransactionTemplate(manager);
    }

    /** The production wiring under proof: tenant-scoped views, the guard, the body port, the real socket send. */
    private ApiTargetCalls calls() {
        return new ApiTargetCalls(owner, TENANT, resolver, dialer, bodyPort());
    }

    private Stage2Send stage2Send() {
        return new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "CUST-1", endpointId, false);
    }

    private static ApiTargetBody bodyPort() {
        return send -> Optional.of(new ApiTargetBody.ApiWireBody("application/json",
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The bean wiring the adapter configuration will produce (the green change wires it exactly this way); the
     * container test builds it by hand from the same parts - the views reader runs on the transaction's
     * connection, so the send itself is the tenant-stamped transaction (R08/R11).
     */
    private static final class ApiTargetCalls {

        private final DataSource owner;
        private final UUID tenant;
        private final io.integrationplatform.config.destination.HostResolver resolver;
        private final io.integrationplatform.config.destination.SocketDialer dialer;
        private final ApiTargetBody bodyPort;

        ApiTargetCalls(DataSource owner, UUID tenant, io.integrationplatform.config.destination.HostResolver resolver,
                io.integrationplatform.config.destination.SocketDialer dialer, ApiTargetBody bodyPort) {
            this.owner = owner;
            this.tenant = tenant;
            this.resolver = resolver;
            this.dialer = dialer;
            this.bodyPort = bodyPort;
        }

        void sendInTenantTransaction(Stage2Send send) {
            new TransactionTemplate(new DataSourceTransactionManager(owner)).execute(status -> {
                target().call(send);
                return null;
            });
        }

        private io.integrationplatform.routing.stage2.TargetCall target() {
            JdbcClient jdbc = JdbcClient.create(owner);
            TargetEndpointViews views = new JdbcTargetEndpointViews(jdbc, () -> Optional.of(tenant));
            return new ApiTargetCall(views,
                    new DestinationGuard(AllowList.of(List.of(ApiAdapterWireMock.HOST)), resolver), bodyPort,
                    new SocketHttpSend(dialer));
        }
    }
}