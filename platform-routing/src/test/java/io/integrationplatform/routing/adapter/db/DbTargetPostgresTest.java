package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-149 against a real PostgreSQL as the DB target (E16-F1-S2; E16-C6): rows land in the approved table through a
 * parameterised insert as the restricted account, an ambiguous retry holds exactly one row per delivery id, an
 * unlisted table is refused with nothing written, the restricted account has no DDL rights, a closed port is
 * TRANSIENT, and test-connection answers SUCCESS / DATABASE_UNREACHABLE.
 */
@Tag("container")
class DbTargetPostgresTest {

    static final PostgreSQLContainer TARGET = DbTargetContainers.target();
    static final String RESTRICTED_PASSWORD = "restricted-" + UUID.randomUUID().toString().substring(0, 8);

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final UUID endpointId = UUID.randomUUID();
    final UUID deliveryId = UUID.randomUUID();

    Stage2Send send() {
        return new Stage2Send(deliveryId, UUID.randomUUID(), null, endpointId, true);
    }

    DbSettings settings(String table) {
        return new DbSettings(TARGET.getHost(), TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                TARGET.getDatabaseName(), "restricted", table, List.of("orders_in"));
    }

    DbTargetCall call(DbSettings settings, char[] password) {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of(TARGET.getHost())),
                name -> List.of(InetAddress.getLoopbackAddress()), List.of(TARGET.getHost()),
                List.of(TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
        DbOutbound outbound = new DbTargetOutbound(
                id -> Optional.of(new DbTargets.DbTarget(settings, password)),
                s -> Optional.of(List.of(Map.of("awb", "ABX1", "pieces", 2))));
        return new DbTargetCall(outbound, new JdbcDbSender(Duration.ofSeconds(5)), guard,
                CircuitBreakerRegistry.ofDefaults(), meters, ObservationRegistry.create());
    }

    int count(String query) throws SQLException {
        try (Connection owner = owner();
                Statement sql = owner.createStatement();
                ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
    }

    @BeforeAll
    static void start() throws SQLException {
        TARGET.start();
        try (Connection owner = DriverManager.getConnection(TARGET.getJdbcUrl(), TARGET.getUsername(),
                TARGET.getPassword()); Statement sql = owner.createStatement()) {
            sql.execute("CREATE ROLE restricted LOGIN PASSWORD '" + RESTRICTED_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE"); // no DDL rights: only what GRANT names
            sql.execute("CREATE TABLE orders_in (id bigserial PRIMARY KEY, delivery_id uuid NOT NULL UNIQUE,"
                    + " awb text NOT NULL, pieces integer NOT NULL)");
            sql.execute("GRANT SELECT, INSERT ON orders_in TO restricted");
        }
    }

    @AfterAll
    static void stop() {
        TARGET.stop();
    }

    @Test
    void rowsLandInAnApprovedTableThroughTheRestrictedAccount() throws Exception {
        call(settings("orders_in"), RESTRICTED_PASSWORD.toCharArray()).call(send());

        assertThat(count("select count(*) from orders_in where awb = 'ABX1' and pieces = 2")).isEqualTo(1);
        assertThat(meters.get("db.target.outcome").tag("target_type", "DB").tag("outcome", "SUCCESS").counter())
                .isNotNull();
    }

    @Test
    void anAmbiguousOutcomeNeverDoubleInsertsOnRetry() throws Exception {
        // the ambiguous-commit crash: the retry re-runs the same delivery id and ON CONFLICT keeps one row
        call(settings("orders_in"), RESTRICTED_PASSWORD.toCharArray()).call(send());
        call(settings("orders_in"), RESTRICTED_PASSWORD.toCharArray()).call(send());

        assertThat(count("select count(*) from orders_in where delivery_id = '" + deliveryId + "'")).isEqualTo(1);
    }

    @Test
    void anUnlistedTableIsRefusedAndNothingIsWritten() throws Exception {
        assertThatThrownBy(() -> call(settings("users"), RESTRICTED_PASSWORD.toCharArray()).call(send()))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);

        try (Connection owner = owner(); Statement sql = owner.createStatement();
                ResultSet rs = sql.executeQuery("select to_regclass('users')")) {
            assertThat(rs.next() && rs.getString(1) == null).isTrue(); // table 'users' was never created
        }
    }

    @Test
    void theRestrictedAccountHasNoDdlRights() throws SQLException {
        try (Connection restricted = DriverManager.getConnection(TARGET.getJdbcUrl(), "restricted",
                RESTRICTED_PASSWORD); Statement sql = restricted.createStatement()) {
            assertThat(sql.execute("CREATE TABLE poke (id int)")).as("CREATE denied").isFalse();
        } catch (SQLException denied) {
            assertThat(denied.getSQLState()).isEqualTo("42501"); // permission denied for schema public
            return;
        }
        throw new AssertionError("the restricted account created a table - no DDL rights were expected");
    }

    @Test
    void aClosedTargetPortIsTransient() throws SQLException {
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("select 1"); // sanity: the container is up, the closed port is the refusal source
        }
        DbSettings closed = new DbSettings(TARGET.getHost(), TARGET.getMappedPort(
                PostgreSQLContainer.POSTGRESQL_PORT) + 9000, TARGET.getDatabaseName(), "restricted", "orders_in",
                List.of("orders_in"));

        assertThatThrownBy(() -> call(closed, RESTRICTED_PASSWORD.toCharArray()).call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    void testConnectionAnswersSuccessThenUnreachable() throws SQLException {
        DbTestConnection connection = new DbTestConnection(
                new DestinationGuard(AllowList.of(List.of(TARGET.getHost())),
                        name -> List.of(InetAddress.getLoopbackAddress()), List.of(TARGET.getHost()),
                        List.of(TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))),
                (host, port, timeout) -> { /* the probe answers: reachable */ }, Duration.ofSeconds(5));
        String json = "{\"host\":\"" + TARGET.getHost() + "\",\"port\":"
                + TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + ",\"database\":\""
                + TARGET.getDatabaseName() + "\"}";

        assertThat(connection.test(UUID.randomUUID(), json).get("result")).isEqualTo("SUCCESS");

        DbSettings refused = new DbSettings(TARGET.getHost(), TARGET.getMappedPort(
                PostgreSQLContainer.POSTGRESQL_PORT) + 9000, TARGET.getDatabaseName(), null, null, List.of());
        DbTestConnection stopped = new DbTestConnection(guard(TARGET.getHost()), (host, port, timeout) -> {
            throw new java.io.IOException("not answering");
        }, Duration.ofSeconds(5));

        assertThat(stopped.test(UUID.randomUUID(), "{\"host\":\"" + TARGET.getHost() + "\",\"port\":"
                + refused.port() + ",\"database\":\"db\"}").get("reason")).isEqualTo("DATABASE_UNREACHABLE");
    }

    private DestinationGuard guard(String host) {
        return new DestinationGuard(AllowList.of(List.of(host)), name -> List.of(InetAddress.getLoopbackAddress()),
                List.of(host), List.of(TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
    }
}