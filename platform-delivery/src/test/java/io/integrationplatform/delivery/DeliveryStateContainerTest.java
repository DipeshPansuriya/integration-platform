package io.integrationplatform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E2-F2-S1 (T-012, T-013): the whole walk PENDING -> SENDING -> RETRY_WAIT -> DUE -> SENDING -> DELIVERED is
 * stored transition by transition with its time, illegal moves change nothing, and a RETRY_WAIT delivery with its
 * stored retry time survives a restart (the pool is closed and reopened; nothing lives in memory).
 */
@Tag("container")
class DeliveryStateContainerTest {

    // One day before the claim-store tests, as the original 10-08 vs 10-09 dates were: both classes share one database
    // and TENANT_A, so this keeps one class's RETRY_WAIT rows out of the other's same-day lockDue windows. Yesterday's
    // day partition always exists (the migration pre-creates CURRENT_DATE-1..+7 when the container starts).
    private static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);

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

    private DeliveryState state(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id))
                .orElseThrow(() -> new AssertionError("delivery row missing: " + id));
    }

    @Test
    void theFullWalkIsStoredTransitionByTransitionWithItsTime() {
        UUID id = UUID.randomUUID();
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        Instant at = DAY.plus(10, ChronoUnit.HOURS);

        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            var repository = repository();
            repository.create(id, DeliveryTestDatabase.INTERFACE_ID, "walk-key", 9L,
                    DeliveryTestDatabase.TARGET_ID, at);
            repository.claimSend(id, firstToken, at.plusSeconds(1));
            repository.scheduleRetry(id, firstToken, at.plusSeconds(61), at.plusSeconds(2));
            repository.redrive(id, at.plusSeconds(61));
            repository.claimSend(id, secondToken, at.plusSeconds(62));
            repository.markDelivered(id, secondToken, at.plusSeconds(63));
        });

        assertThat(state(id)).isEqualTo(DeliveryState.DELIVERED);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().nextAttemptAt(id))).isEmpty();

        List<DeliveryEventType> events = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> jdbc.sql("select type from public.delivery_event where delivery_id = :id::uuid order by id")
                        .params(java.util.Map.of("id", id.toString()))
                        .query((RowMapper<DeliveryEventType>) (rs, rowNum) -> DeliveryEventType.valueOf(rs.getString(1)))
                        .list());
        assertThat(events).containsExactly(DeliveryEventType.CLAIM, DeliveryEventType.RETRY,
                DeliveryEventType.REDRIVE, DeliveryEventType.CLAIM, DeliveryEventType.DELIVER);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> jdbc.sql("select occurred_at is not null from public.delivery_event where delivery_id"
                        + " = :id::uuid").params(java.util.Map.of("id", id.toString()))
                        .query(Boolean.class).list()))
                .containsExactly(true, true, true, true, true);
    }

    @Test
    void anIllegalMoveAtTheDatabaseLevelChangesNothing() {
        UUID id = UUID.randomUUID();
        Instant at = DAY.plus(11, ChronoUnit.HOURS);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().create(id,
                DeliveryTestDatabase.INTERFACE_ID, "illegal-key", 9L, DeliveryTestDatabase.TARGET_ID, at));

        // PENDING -> DELIVERED is outside the register: the conditional update matches 0 rows, false not a throw,
        // and no event is written (the guard of T-011 in code plus this token/state condition at the table).
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().markDelivered(id, UUID.randomUUID(), at))).isFalse();
        assertThat(state(id)).isEqualTo(DeliveryState.PENDING);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> jdbc.sql("select count(*) from public.delivery_event where delivery_id = :id::uuid")
                        .params(java.util.Map.of("id", id.toString())).query(Long.class).single())).isZero();
    }

    @Test
    void retryWaitSurvivesARestartWithItsStoredRetryTime() {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant at = DAY.plus(12, ChronoUnit.HOURS);
        Instant next = at.plusSeconds(300);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            var repository = repository();
            repository.create(id, DeliveryTestDatabase.INTERFACE_ID, "restart-key", 9L,
                    DeliveryTestDatabase.TARGET_ID, at);
            repository.claimSend(id, token, at.plusSeconds(1));
            repository.scheduleRetry(id, token, next, at.plusSeconds(2));
        });
        Instant storedNext = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().nextAttemptAt(id)).orElseThrow();

        // Restart: this JVM forgets every object - a fresh pool, fresh repositories, same database.
        pool.close();
        pool = db.appPool();
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);

        assertThat(state(id)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().nextAttemptAt(id)))
                .contains(storedNext);
    }

    @Test
    void anUnknownTenantCannotSeeTheDelivery() {
        UUID id = UUID.randomUUID();
        Instant at = DAY.plus(13, ChronoUnit.HOURS);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().create(id,
                DeliveryTestDatabase.INTERFACE_ID, "rls-see", 9L, DeliveryTestDatabase.TARGET_ID, at));

        var repositoryB = new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_B));
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_B, () -> repositoryB.state(id))).isEmpty();
        assertThatThrownBy(() -> tx.inBareTx(() -> repository().state(id)))
                .isInstanceOf(DataAccessException.class);
    }
}
