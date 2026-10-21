package io.integrationplatform.delivery.e10;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryCircuitFacts;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcSendOutcomes;
import io.integrationplatform.domain.delivery.DeliveryState;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E10 T96 / T-183 wiring (ADR-010 sections 5 to 7), on the real partitioned tables as the restricted login: a
 * circuit-open deferral is {@code SENDING -> RETRY_WAIT} under the claim token with a {@code CIRCUIT_DEFER} event and
 * NO attempt row (it is not a call); {@link JdbcDeliveryCircuitFacts} names the target of a delivery whose LAST event
 * is that deferral - and only that - so the retry trigger's half-open cap counts exactly those.
 */
@Tag("container")
class CircuitDeferContainerTest {

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;

    @BeforeEach
    void setUp() {
        pool = db.appPool();
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    private JdbcSendOutcomes outcomes() {
        return new JdbcSendOutcomes(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    private JdbcDeliveryCircuitFacts facts(UUID tenant) {
        return new JdbcDeliveryCircuitFacts(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private UUID sending(UUID token) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository().create(id, DeliveryTestDatabase.INTERFACE_ID, "circuit-" + id, 1L,
                    DeliveryTestDatabase.TARGET_ID, now);
            assertThat(repository().claimSend(id, token, now)).isTrue();
        });
        return id;
    }

    private List<String> events(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select type || ':' || coalesce(detail, '-') from public.delivery_event"
                                + " where delivery_id = :id::uuid order by id")
                .params(Map.of("id", id.toString())).query(String.class).list());
    }

    @Test
    @DisplayName("Circuit open: SENDING -> RETRY_WAIT at the given time, a CIRCUIT_DEFER event, no attempt row")
    void circuitOpenWaitsWithoutAnAttempt() {
        UUID token = UUID.randomUUID();
        UUID id = sending(token);
        Instant next = Instant.now().plus(Duration.ofSeconds(30)).truncatedTo(ChronoUnit.MILLIS);

        boolean moved = tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> outcomes().deferCircuitOpen(id, token, next));

        assertThat(moved).isTrue();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)))
                .contains(DeliveryState.RETRY_WAIT);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countAttempts(id)))
                .as("no call left the process: no attempt row").isZero();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().nextAttemptAt(id))).contains(next);
        assertThat(events(id)).last().asString().startsWith("CIRCUIT_DEFER");
    }

    @Test
    @Tag("negative")
    @DisplayName("A stale or foreign claim token moves nothing and records LATE_OUTCOME")
    void aStaleTokenMovesNothing() {
        UUID id = sending(UUID.randomUUID());

        boolean moved = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> outcomes().deferCircuitOpen(id, UUID.randomUUID(), Instant.now().plusSeconds(30)));

        assertThat(moved).isFalse();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)))
                .contains(DeliveryState.SENDING);
        assertThat(events(id)).last().asString().startsWith("LATE_OUTCOME");
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> facts(DeliveryTestDatabase.TENANT_A)
                .circuitDeferredTarget(id))).isEmpty();
    }

    @Test
    @DisplayName("Circuit facts: the target of a delivery whose last event is CIRCUIT_DEFER; empty once re-driven")
    void factsNameTheDeferredTargetUntilTheRedrive() {
        UUID token = UUID.randomUUID();
        UUID id = sending(token);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> outcomes().deferCircuitOpen(id, token, Instant.now().plusSeconds(30)));

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> facts(DeliveryTestDatabase.TENANT_A).circuitDeferredTarget(id)))
                .contains(DeliveryTestDatabase.TARGET_ID);

        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> assertThat(repository().redrive(id, Instant.now())).isTrue());

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> facts(DeliveryTestDatabase.TENANT_A).circuitDeferredTarget(id)))
                .as("REDRIVE is now the last event: no longer counted against the half-open cap").isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("Circuit facts: a delivery waiting for another reason, or another tenant's delivery, is not circuit-deferred")
    void otherDeliveriesAreNotCircuitDeferred() {
        UUID token = UUID.randomUUID();
        UUID retrying = sending(token);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> assertThat(repository().scheduleRetry(retrying, token, Instant.now().plusSeconds(30), Instant.now()))
                        .isTrue());
        UUID deferredToken = UUID.randomUUID();
        UUID deferred = sending(deferredToken);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> outcomes().deferCircuitOpen(deferred, deferredToken, Instant.now().plusSeconds(30)));

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> facts(DeliveryTestDatabase.TENANT_A).circuitDeferredTarget(retrying))).isEmpty();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_B,
                () -> facts(DeliveryTestDatabase.TENANT_B).circuitDeferredTarget(deferred))).isEmpty();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> facts(DeliveryTestDatabase.TENANT_A).circuitDeferredTarget(UUID.randomUUID()))).isEmpty();
    }
}
