package io.integrationplatform.delivery.internal.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.replay.OperatorActionStore.Target;
import io.integrationplatform.domain.delivery.DeliveryState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T111 on the real partitioned table (run with {@code -Pcontainer}): the lock read returns the state and both pins
 * for the bound tenant only, the moves are conditional on the source state, and each move appends its
 * {@code delivery_event} (REPLAY or REDRIVE).
 */
@Tag("container")
class JdbcOperatorActionStoreContainerTest {

    /** Today's day partition always exists (the migration pre-creates CURRENT_DATE-1..+7). */
    private static final Instant TODAY = Instant.now().truncatedTo(ChronoUnit.DAYS);

    private final UUID mapping = UUID.randomUUID();
    private final UUID contract = UUID.randomUUID();
    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;

    @BeforeEach
    void setUp() {
        pool = db.appPool(4);
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private JdbcOperatorActionStore store(UUID tenant) {
        return new JdbcOperatorActionStore(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private UUID insert(UUID tenant, String state, String deadLetterClass) {
        UUID id = UUID.randomUUID();
        tx.inTenantTx(tenant, () -> jdbc.sql("insert into public.delivery (id, tenant_id, state, created_at,"
                        + " updated_at, target_id, mapping_version_id, contract_version_id, dead_letter_class)"
                        + " values (:id::uuid, current_setting('app.tenant_id')::uuid, :state, :at, :at,"
                        + " :target::uuid, :mapping::uuid, :contract::uuid, :dlc)")
                .param("id", id.toString())
                .param("state", state)
                .param("at", java.sql.Timestamp.from(TODAY))
                .param("target", DeliveryTestDatabase.TARGET_ID.toString())
                .param("mapping", mapping.toString())
                .param("contract", contract.toString())
                .param("dlc", deadLetterClass)
                .update());
        return id;
    }

    private String state(UUID tenant, UUID id) {
        return tx.inTenantTx(tenant, () -> jdbc.sql("select state from public.delivery where id = :id::uuid")
                .param("id", id.toString()).query(String.class).single());
    }

    private List<String> events(UUID tenant, UUID id) {
        return tx.inTenantTx(tenant, () -> jdbc.sql(
                        "select type from public.delivery_event where delivery_id = :id::uuid order by id")
                .param("id", id.toString()).query(String.class).list());
    }

    @Test
    void theLockReadReturnsTheStateAndBothPinsForTheBoundTenant() {
        UUID id = insert(DeliveryTestDatabase.TENANT_A, "DEAD_LETTERED", "PERMANENT");
        Target target = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store(DeliveryTestDatabase.TENANT_A).lockForAction(id).orElseThrow());
        assertThat(target).isEqualTo(new Target(id, DeliveryState.DEAD_LETTERED, mapping, contract));
    }

    @Test
    void anotherTenantsDeliveryIsInvisible() {
        UUID id = insert(DeliveryTestDatabase.TENANT_A, "DEAD_LETTERED", "PERMANENT");
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_B,
                () -> store(DeliveryTestDatabase.TENANT_B).lockForAction(id))).isEmpty();
    }

    @Test
    void replayMovesDeadLetteredToDueAndAppendsTheReplayEvent() {
        UUID id = insert(DeliveryTestDatabase.TENANT_A, "DEAD_LETTERED", "PERMANENT");
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store(DeliveryTestDatabase.TENANT_A).replay(id, Instant.now()))).isTrue();
        assertThat(state(DeliveryTestDatabase.TENANT_A, id)).isEqualTo("DUE");
        assertThat(events(DeliveryTestDatabase.TENANT_A, id)).containsExactly("REPLAY");
    }

    @Test
    void retryNowMovesRetryWaitToDueAndAppendsTheRedriveEvent() {
        UUID id = insert(DeliveryTestDatabase.TENANT_A, "RETRY_WAIT", null);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store(DeliveryTestDatabase.TENANT_A).retryNow(id, Instant.now()))).isTrue();
        assertThat(state(DeliveryTestDatabase.TENANT_A, id)).isEqualTo("DUE");
        assertThat(events(DeliveryTestDatabase.TENANT_A, id)).containsExactly("REDRIVE");
    }

    @Test
    void theMovesAreConditionalOnTheSourceState() {
        UUID retryWait = insert(DeliveryTestDatabase.TENANT_A, "RETRY_WAIT", null);
        UUID deadLettered = insert(DeliveryTestDatabase.TENANT_A, "DEAD_LETTERED", "PERMANENT");
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store(DeliveryTestDatabase.TENANT_A).replay(retryWait, Instant.now()))).isFalse();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store(DeliveryTestDatabase.TENANT_A).retryNow(deadLettered, Instant.now()))).isFalse();
        assertThat(state(DeliveryTestDatabase.TENANT_A, retryWait)).isEqualTo("RETRY_WAIT");
        assertThat(state(DeliveryTestDatabase.TENANT_A, deadLettered)).isEqualTo("DEAD_LETTERED");
    }
}
