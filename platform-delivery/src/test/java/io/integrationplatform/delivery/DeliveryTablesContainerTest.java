package io.integrationplatform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E2-F2-S2 (T-091) and the R15 append-only proof (T-012, E2-F2-S1 @negative): delivery and delivery_attempt are
 * partitioned by day from their FIRST migration, purge is a partition drop that touches nothing else, and the
 * attempt ledger refuses UPDATE and DELETE for the application role.
 */
@Tag("container")
class DeliveryTablesContainerTest {

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

    private void createDays(String table, List<String> isoDates) throws SQLException {
        for (String day : isoDates) {
            db.createDayPartition(table, day);
        }
    }

    private JdbcDeliveryRepository repository(UUID tenant) {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private void createDelivery(UUID id, String businessKey, long keySeq, UUID tenant, Instant createdAt) {
        tx.inTenantTx(tenant, () -> repository(tenant).create(id, DeliveryTestDatabase.INTERFACE_ID, businessKey,
                keySeq, DeliveryTestDatabase.TARGET_ID, createdAt));
    }

    @Test
    void dailyPartitionsExistFromTheFirstDeliveryMigration() {
        String deliveryKey = jdbc.sql("select pg_get_partkeydef('public.delivery'::regclass)")
                .query(String.class).single();
        String attemptKey = jdbc.sql("select pg_get_partkeydef('public.delivery_attempt'::regclass)")
                .query(String.class).single();
        assertThat(deliveryKey).isEqualTo("RANGE (created_at)");
        assertThat(attemptKey).isEqualTo("RANGE (created_at)");
    }

    @Test
    void droppingOneDayTouchesNothingElse() throws Exception {
        List<String> days = List.of("2026-10-03", "2026-10-04", "2026-10-05");
        createDays("delivery", days);
        for (int i = 0; i < days.size(); i++) {
            createDelivery(UUID.randomUUID(), "key-" + i, 1L + i, DeliveryTestDatabase.TENANT_A,
                    Instant.parse(days.get(i) + "T10:00:00Z"));
        }
        assertThat(jdbc.sql("select count(*) from public.delivery").query(Long.class).single()).isEqualTo(3);

        // Purge: the maintenance identity (never the application role, T6) drops ONE day's partition. A live
        // transaction keeps a row in the kept 2026-10-05 partition (locking the child directly, not the parent)
        // across the drop; the drop must not wait for it and no lock may outlive the drop statement.
        try (Connection holder = pool.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement set = holder.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                set.setString(1, DeliveryTestDatabase.TENANT_A.toString());
                set.execute();
            }
            try (Statement touch = holder.createStatement()) {
                touch.execute("update public." + db.dayPartitionName("delivery", "2026-10-05")
                        + " set bulkhead_deferrals = bulkhead_deferrals");
            }

            long started = System.nanoTime();
            db.asOwner("drop table public." + db.dayPartitionName("delivery", "2026-10-04"));
            long dropMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(dropMillis).as("dropping one partition waits on nothing from the other days")
                    .isLessThan(2_000);

            holder.commit();
        }

        assertThat(jdbc.sql("select count(*) from public.delivery").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select tableoid::regclass::text from public.delivery"
                        + " where created_at >= '2026-10-05'").query(String.class).single())
                .isEqualTo(db.dayPartitionName("delivery", "2026-10-05"));
        // the two kept days are exactly the untouched rows
        assertThat(jdbc.sql("select business_key from public.delivery order by business_key")
                .query(String.class).list()).containsExactly("key-0", "key-2");
    }

    @Test
    void theApplicationRoleCannotUpdateOrDeleteAttemptRows() throws Exception {
        createDays("delivery", List.of("2026-10-06"));
        createDays("delivery_attempt", List.of("2026-10-06"));

        UUID deliveryId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        createDelivery(deliveryId, "append-key", 1L, DeliveryTestDatabase.TENANT_A, at);
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository(DeliveryTestDatabase.TENANT_A)
                .recordAttempt(deliveryId, 2, OutcomeClass.TRANSIENT, at, at.plusSeconds(1), "connection reset"));

        assertThatThrownBy(() -> jdbc.sql("update public.delivery_attempt set detail = 'rewritten'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from public.delivery_attempt").update())
                .isInstanceOf(DataAccessException.class);
        // history stays exactly as appended
        assertThat(jdbc.sql("select detail from public.delivery_attempt").query(String.class).single())
                .isEqualTo("connection reset");
        // deliveries are state-changed, never deleted, by the application role
        assertThatThrownBy(() -> jdbc.sql("delete from public.delivery where id = :id::uuid")
                .params(Map.of("id", deliveryId.toString())).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void everyDeliveryTableCarriesTheErrorDenyRlsIdiom() {
        for (String table : List.of("delivery", "delivery_attempt", "delivery_event")) {
            assertThatThrownBy(() -> jdbc.sql("select 1 from public." + table).query(Integer.class).list())
                    .as(table + " without a stamped tenant fails the statement (ADR-009 Decision 5)")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void oneTenantNeverReadsAnotherTenantsDelivery() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Instant at = Instant.now();
        createDelivery(idA, "a-key", 1L, DeliveryTestDatabase.TENANT_A, at);
        createDelivery(idB, "b-key", 1L, DeliveryTestDatabase.TENANT_B, at);

        List<UUID> visibleToA = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository(DeliveryTestDatabase.TENANT_A).findIds());
        assertThat(visibleToA).contains(idA).doesNotContain(idB);
    }
}
