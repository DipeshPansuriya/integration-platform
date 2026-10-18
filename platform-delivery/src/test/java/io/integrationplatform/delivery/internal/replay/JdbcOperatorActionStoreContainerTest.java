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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    /**
     * T165 item 4: two operators replay the same dead-lettered delivery at once. The first holds the
     * {@code FOR UPDATE} lock until it commits; the second's lock read waits, then sees the committed DUE and is
     * refused as wrong-state (the {@code DeliveryOperatorActions.act} check). Exactly one REPLAY event exists. Without
     * the lock the second read would see DEAD_LETTERED from its snapshot and only the conditional update would save it.
     */
    @Test
    void twoConcurrentReplaysOfOneDeliveryLetExactlyOneWin() throws Exception {
        UUID id = insert(DeliveryTestDatabase.TENANT_A, "DEAD_LETTERED", "PERMANENT");
        CountDownLatch firstLocked = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> attemptReplay(id, () -> {
            firstLocked.countDown();
            sleep(750); // hold the row lock while the second caller reaches its lock read
        }));
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> attemptReplay(id, () -> { }));

        assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo("MOVED_TO_DUE");
        assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo("WRONG_STATE saw DUE");
        assertThat(state(DeliveryTestDatabase.TENANT_A, id)).isEqualTo("DUE");
        assertThat(events(DeliveryTestDatabase.TENANT_A, id)).containsExactly("REPLAY");
    }

    /** The lock-check-move of {@code DeliveryOperatorActions.act} for a replay, in one tenant transaction. */
    private String attemptReplay(UUID id, Runnable afterLock) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            JdbcOperatorActionStore store = store(DeliveryTestDatabase.TENANT_A);
            Target target = store.lockForAction(id).orElseThrow();
            afterLock.run();
            if (target.state() != DeliveryState.DEAD_LETTERED) {
                return "WRONG_STATE saw " + target.state();
            }
            return store.replay(id, Instant.now()) ? "MOVED_TO_DUE" : "WRONG_STATE lock broken";
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
