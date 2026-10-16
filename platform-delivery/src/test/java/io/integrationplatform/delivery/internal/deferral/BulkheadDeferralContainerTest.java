package io.integrationplatform.delivery.internal.deferral;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.ordering.JdbcOrderedStateChange;
import io.integrationplatform.delivery.ordering.OrderingKey;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.domain.delivery.DeliveryState;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-198 + the T-177 pre-claim facts read on the real PostgreSQL (E9-F1-S4, ADR-010 section 2): a full-bulkhead
 * deferral is one conditional {@code PENDING/DUE -> RETRY_WAIT} update with the {@code bulkhead_deferrals}
 * increment, a {@code BULKHEAD_FULL} delivery event and ZERO attempt rows, the ordering-key lock taken first
 * through lane C's {@link JdbcOrderedStateChange} (skipped for a {@code NONE} interface); a deferral whose
 * conditional update matches 0 rows (already claimed or moved) is a {@code false} - acknowledged, no effect,
 * no event; the 1-2-4-8-16-30-30 series runs across consecutive deferrals; a successful claim resets the count
 * to 0 so the next deferral starts the series again (the claim-side reset landed with T128); and the facts read
 * hands the dispatch exactly what it needs BEFORE taking a permit (target, derived ordering key, count). The
 * dispatch calls both under the record's bound tenant with no transaction open - each opens its own (R11).
 */
@Tag("container")
class BulkheadDeferralContainerTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.DAYS).plusSeconds(43_200);

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;
    private JdbcDeliveryRepository repository;

    @BeforeEach
    void setUp() {
        pool = db.appPool(4);
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
        repository = new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager((DataSource) pool));
    }

    private JdbcBulkheadDeferrals deferrals() {
        TransactionTemplate transactions = transactions();
        JdbcOrderedStateChange ordered =
                new JdbcOrderedStateChange(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                        transactions);
        return new JdbcBulkheadDeferrals(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                transactions, ordered);
    }

    private JdbcDeliveryBulkheadFacts facts() {
        return new JdbcDeliveryBulkheadFacts(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                transactions());
    }

    private UUID newDelivery(String businessKey) {
        UUID id = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.create(id,
                DeliveryTestDatabase.INTERFACE_ID, businessKey, businessKey == null ? null : 1L,
                DeliveryTestDatabase.TARGET_ID, NOW));
        return id;
    }

    /** The dispatch's order of one deferral: read facts (no lock), then defer with the row's own count. */
    private boolean deferWithFacts(UUID deliveryId, double jitter) {
        BulkheadFacts read = facts().forDelivery(deliveryId).orElseThrow();
        return deferrals().defer(deliveryId, NOW, read.bulkheadDeferrals(), jitter);
    }

    private Optional<DeliveryState> state(UUID deliveryId) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.state(deliveryId));
    }

    private Optional<Instant> nextAttemptAt(UUID deliveryId) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.nextAttemptAt(deliveryId));
    }

    private int attempts(UUID deliveryId) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.countAttempts(deliveryId));
    }

    private int bulkheadFullEvents(UUID deliveryId) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository.countEvents(deliveryId, DeliveryEventType.BULKHEAD_FULL));
    }

    private int deferralCount(UUID deliveryId) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select bulkhead_deferrals from public.delivery where id = :id::uuid")
                .params(Map.of("id", deliveryId)).query(Integer.class).single());
    }

    private List<String> eventTypes(UUID deliveryId) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select type from public.delivery_event where delivery_id = :id::uuid order by occurred_at")
                .params(Map.of("id", deliveryId)).query(String.class).list());
    }

    private void redrive(UUID deliveryId) {
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository.redrive(deliveryId, NOW);
        });
    }

    @Test
    @DisplayName("a PENDING delivery defers: RETRY_WAIT with the 1 s first wait, count 1, a BULKHEAD_FULL event, 0 attempt rows")
    void firstDeferralMovesPendingToRetryWaitWithoutAnAttemptRow() {
        UUID delivery = newDelivery("key-1");
        assertThat(deferWithFacts(delivery, 1.0)).isTrue();

        assertThat(state(delivery)).contains(DeliveryState.RETRY_WAIT);
        assertThat(nextAttemptAt(delivery)).contains(NOW.plusSeconds(1));
        assertThat(deferralCount(delivery)).isEqualTo(1);
        assertThat(eventTypes(delivery)).containsExactly(DeliveryEventType.BULKHEAD_FULL.name());
        assertThat(attempts(delivery)).as("a deferral is not a call").isZero();
    }

    @Test
    @DisplayName("seven consecutive deferrals (PENDING, then DUE after each re-drive) wait 1, 2, 4, 8, 16, 30, 30 s at jitter 1.0 - 0 attempt rows across the series")
    void theBackoffSeriesRunsToTheThirtySecondCap() {
        UUID delivery = newDelivery("key-series");
        long[] expectedWaits = {1, 2, 4, 8, 16, 30, 30};
        for (int i = 0; i < expectedWaits.length; i++) {
            assertThat(deferWithFacts(delivery, 1.0)).as("deferral %d", i + 1).isTrue();
            assertThat(nextAttemptAt(delivery))
                    .as("wait %d is %d s", i + 1, expectedWaits[i])
                    .contains(NOW.plusSeconds(expectedWaits[i]));
            if (i < expectedWaits.length - 1) {
                redrive(delivery); // RETRY_WAIT -> DUE: the next deferral runs on a DUE row
            }
        }
        assertThat(deferralCount(delivery)).isEqualTo(7);
        assertThat(attempts(delivery)).as("0 attempt rows across the whole series").isZero();
        assertThat(bulkheadFullEvents(delivery)).isEqualTo(7);
    }

    @Test
    @DisplayName("full jitter: a 0.5 share halves the series wait (position 4: 16 s becomes 8 s)")
    void jitterScalesTheStoredWait() {
        UUID delivery = newDelivery("key-jitter");
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            jdbc.sql("update public.delivery set bulkhead_deferrals = 4 where id = :id::uuid")
                    .params(Map.of("id", delivery)).update();
        });

        assertThat(deferrals().defer(delivery, NOW, 4, 0.5)).isTrue();
        assertThat(nextAttemptAt(delivery)).contains(NOW.plus(Duration.ofSeconds(8)));
        assertThat(deferralCount(delivery)).isEqualTo(5);
    }

    @Test
    @DisplayName("the update is conditional on state IN (PENDING, DUE): a claimed (SENDING) delivery defers 0 rows - false, no event, untouched")
    void deferralOfAClaimedDeliveryIsAcknowledgedWithNoEffect() {
        UUID delivery = newDelivery("key-race");
        UUID claimToken = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository.claimSend(delivery, claimToken, NOW);
        });

        assertThat(deferrals().defer(delivery, NOW, 0, 1.0)).as("0 rows = no effect, never an exception").isFalse();
        assertThat(state(delivery)).contains(DeliveryState.SENDING);
        assertThat(deferralCount(delivery)).isZero();
        assertThat(eventTypes(delivery)).doesNotContain(DeliveryEventType.BULKHEAD_FULL.name());
    }

    @Test
    @DisplayName("the update is conditional on state IN (PENDING, DUE): a RETRY_WAIT delivery defers 0 rows - its stored wait is kept")
    void deferralOfARetryWaitDeliveryHasNoEffect() {
        UUID delivery = newDelivery("key-waiting");
        assertThat(deferWithFacts(delivery, 1.0)).isTrue(); // now RETRY_WAIT at +1 s, count 1

        assertThat(deferrals().defer(delivery, NOW, 1, 1.0)).isFalse();
        assertThat(nextAttemptAt(delivery)).contains(NOW.plusSeconds(1));
        assertThat(deferralCount(delivery)).isEqualTo(1);
        assertThat(bulkheadFullEvents(delivery)).isEqualTo(1);
    }

    @Test
    @DisplayName("a successful claim resets bulkhead_deferrals to 0 - the next deferral restarts the series at 1 s (T128 leg)")
    void successfulClaimResetsTheConsecutiveCount() {
        UUID delivery = newDelivery("key-reset");
        for (int i = 0; i < 3; i++) {
            assertThat(deferWithFacts(delivery, 1.0)).isTrue();
            redrive(delivery);
        }
        assertThat(deferralCount(delivery)).as("three deferrals in a row").isEqualTo(3);

        UUID claimToken = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository.claimSend(delivery, claimToken, NOW);
            repository.scheduleRetry(delivery, claimToken, NOW, NOW); // the send failed: back to RETRY_WAIT
        });
        assertThat(deferralCount(delivery)).as("the claim reset the count (claimSend, T128)").isZero();
        redrive(delivery);

        assertThat(deferWithFacts(delivery, 1.0)).isTrue();
        assertThat(nextAttemptAt(delivery))
                .as("the series restarted from 1 s after the successful claim").contains(NOW.plusSeconds(1));
        assertThat(deferralCount(delivery)).isEqualTo(1);
    }

    @Test
    @DisplayName("ordering NONE (no business key): the deferral still applies - the advisory key lock is skipped")
    void orderingNoneStillDefers() {
        UUID delivery = newDelivery(null);
        BulkheadFacts read = facts().forDelivery(delivery).orElseThrow();
        assertThat(read.orderingKey()).isNull();
        assertThat(deferWithFacts(delivery, 1.0)).isTrue();
        assertThat(state(delivery)).contains(DeliveryState.RETRY_WAIT);
    }

    @Test
    @DisplayName("facts before the permit: target endpoint, the SAME derived ordering key the claim uses, current count")
    void factsCarryTheTargetAndTheDerivedOrderingKey() {
        String businessKey = "key-facts";
        UUID delivery = newDelivery(businessKey);
        assertThat(deferWithFacts(delivery, 1.0)).isTrue(); // one deferral first: count must read back 1

        Optional<BulkheadFacts> read = facts().forDelivery(delivery);
        assertThat(read).isPresent();
        BulkheadFacts facts = read.get();
        assertThat(facts.targetEndpointId()).isEqualTo(DeliveryTestDatabase.TARGET_ID);
        assertThat(facts.orderingKey()).isEqualTo(
                OrderingKey.recordKeyFor(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.INTERFACE_ID,
                        businessKey));
        assertThat(facts.bulkheadDeferrals()).isEqualTo(1);
    }

    @Test
    @DisplayName("facts for a delivery the tenant cannot see answer empty - the record is acknowledged with no effect")
    void factsOfAnUnseenDeliveryAreEmpty() {
        assertThat(facts().forDelivery(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("facts for an already final (DELIVERED) delivery answer empty")
    void factsOfAFinalDeliveryAreEmpty() {
        UUID delivery = newDelivery("key-final");
        UUID claimToken = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository.claimSend(delivery, claimToken, NOW);
            repository.markDelivered(delivery, claimToken, NOW);
        });

        assertThat(facts().forDelivery(delivery)).isEmpty();
    }
}
