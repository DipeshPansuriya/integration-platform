package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class ApiTargetCallSocketHttpSendContainerTest {

    private static final UUID TENANT = UUID.fromString("eeee0000-0000-0000-0000-00000000000a");
    private static final String PATH = "/orders";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");
    @Container
    static final ApiAdapterWireMock WIREMOCK = new ApiAdapterWireMock();

    private DataSource owner;
    private UUID endpointId;
    private ApiAdapterWireMock wiremock;
    private io.integrationplatform.config.destination.SocketDialer dialer;
    private io.integrationplatform.config.destination.HostResolver resolver;
    private final AtomicInteger idempotency = new AtomicInteger();

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
        }
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