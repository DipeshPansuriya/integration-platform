package io.integrationplatform.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryClaimService.Outcome;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The frozen {@link DeliveryRetryStore} contract proven on the real tables (its javadoc is the spec - what lane
 * B's fake mirrors), plus E2-F2-S4: one claim order (lock, read, hold check, HELD or SENDING) in one transaction,
 * token-conditional finals and the LATE_OUTCOME event on a stale or foreign claim.
 */
@Tag("container")
class DeliveryClaimStoreContainerTest {

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

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    private DeliveryRetryStore store() {
        return new JdbcDeliveryRetryStore(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    private DeliveryClaimService claimService() {
        return new DeliveryClaimService(repository());
    }

    private UUID newDelivery(UUID tenant, String businessKey, long keySeq, Instant at) {
        UUID id = UUID.randomUUID();
        tx.inTenantTx(tenant, () -> new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant))
                .create(id, DeliveryTestDatabase.INTERFACE_ID, businessKey, keySeq,
                        DeliveryTestDatabase.TARGET_ID, at));
        return id;
    }

    private void toRetryWait(UUID id, Instant next, Instant at) {
        UUID token = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            var repository = repository();
            repository.claimSend(id, token, at);
            repository.scheduleRetry(id, token, next, at.plusSeconds(1));
        });
    }

    private DeliveryState state(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id))
                .orElseThrow();
    }

    private long eventCount(UUID id, DeliveryEventType type) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc
                .sql("select count(*) from public.delivery_event where delivery_id = :id::uuid and type = :type")
                .params(Map.of("id", id.toString(), "type", type.name())).query(Long.class).single());
    }

    @Test
    void lockDueTakesTheOldestRetryTimesFirstAndSkipsRowsAnotherTransactionHolds() throws Exception {
        Instant base = Instant.parse("2026-10-09T09:00:00Z");
        UUID young = newDelivery(DeliveryTestDatabase.TENANT_A, "ld-young", 1L, base);
        UUID old = newDelivery(DeliveryTestDatabase.TENANT_A, "ld-old", 2L, base);
        UUID middle = newDelivery(DeliveryTestDatabase.TENANT_A, "ld-mid", 3L, base);
        toRetryWait(young, base.plusSeconds(300), base);
        toRetryWait(old, base.plusSeconds(100), base.plusSeconds(10));
        toRetryWait(middle, base.plusSeconds(200), base.plusSeconds(20));

        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<RetryCandidate>> first = executor.submit(() -> tx.inTenantTx(
                    DeliveryTestDatabase.TENANT_A, () -> {
                        List<RetryCandidate> candidates = store().lockDue(base.plusSeconds(3600), 10);
                        locked.countDown();
                        try {
                            Thread.sleep(1_000); // hold the row locks for the second caller
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return candidates;
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            Future<List<RetryCandidate>> second = executor.submit(() -> tx.inTenantTx(
                    DeliveryTestDatabase.TENANT_A, () -> store().lockDue(base.plusSeconds(3600), 10)));
            List<RetryCandidate> candidates = first.get(30, TimeUnit.SECONDS);
            assertThat(candidates).extracting(RetryCandidate::deliveryId)
                    .containsExactly(old, middle, young); // oldest retry time first
            assertThat(second.get(30, TimeUnit.SECONDS))
                    .as("FOR UPDATE SKIP LOCKED: the second caller never sees the held rows")
                    .isEmpty();
        } finally {
            executor.shutdownNow();
        }

        // after the first transaction released its locks the rows are claimable once more
        List<RetryCandidate> again = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().lockDue(base.plusSeconds(3600), 10));
        assertThat(again).hasSize(3);
        List<RetryCandidate> capped = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().lockDue(base.plusSeconds(3600), 2));
        assertThat(capped).hasSize(2);
    }

    @Test
    void markDueMovesALockedRowAndRefusesOneThatIsNoLongerWaiting() {
        Instant base = Instant.parse("2026-10-09T10:00:00Z");
        UUID id = newDelivery(DeliveryTestDatabase.TENANT_A, "md-key", 1L, base);
        toRetryWait(id, base.plusSeconds(60), base);

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            store().lockDue(base.plusSeconds(3600), 10);
            return store().markDue(id);
        })).isTrue();
        assertThat(state(id)).isEqualTo(DeliveryState.DUE);
        assertThat(eventCount(id, DeliveryEventType.REDRIVE)).isEqualTo(1);

        // second call: the row is no longer RETRY_WAIT -> false, never an exception (port javadoc)
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> store().markDue(id))).isFalse();
    }

    @Test
    void retryAndDeadLetterAreTokenConditionalAndAStaleTokenWritesOnlyALateOutcomeEvent() {
        Instant base = Instant.parse("2026-10-09T11:00:00Z");
        UUID id = newDelivery(DeliveryTestDatabase.TENANT_A, "tc-key", 1L, base);
        UUID token = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().claimSend(id, token, base.plusSeconds(1)));

        // a foreign token: 0 rows -> false, LATE_OUTCOME event, state untouched (E2-F2-S4 @negative)
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().scheduleRetry(id, UUID.randomUUID(), base.plusSeconds(90)))).isFalse();
        assertThat(state(id)).isEqualTo(DeliveryState.SENDING);
        assertThat(eventCount(id, DeliveryEventType.LATE_OUTCOME)).isEqualTo(1);

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().deadLetter(id, token, OutcomeClass.TRANSIENT))).isTrue();
        assertThat(state(id)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> jdbc.sql("select dead_letter_class from public.delivery where id = :id::uuid")
                        .params(Map.of("id", id.toString())).query(String.class).single()))
                .isEqualTo("TRANSIENT");
        // a stale token after the final state: event only
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().scheduleRetry(id, token, base.plusSeconds(120)))).isFalse();
        assertThat(eventCount(id, DeliveryEventType.LATE_OUTCOME)).isEqualTo(2);
    }

    @Test
    void exhaustedRetriesEndInTheDeadLetterWithTheFullAttemptHistory() {
        Instant base = Instant.parse("2026-10-09T12:00:00Z");
        UUID id = newDelivery(DeliveryTestDatabase.TENANT_A, "cap-key", 1L, base);
        for (int attempt = 1; attempt <= 3; attempt++) {
            UUID token = UUID.randomUUID();
            Instant at = base.plusSeconds(attempt * 60L);
            int layer = attempt;
            boolean last = attempt == 3;
            tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
                var repository = repository();
                repository.claimSend(id, token, at);
                repository.recordAttempt(id, 2, OutcomeClass.TRANSIENT, at, at.plusSeconds(5),
                        "transient failure " + layer);
                repository.scheduleRetry(id, token, at.plusSeconds(60), at.plusSeconds(6));
                if (!last) {
                    repository.redrive(id, at.plusSeconds(60));
                }
            });
        }

        List<RetryCandidate> due = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().lockDue(base.plusSeconds(3600), 10));
        assertThat(due).anySatisfy(candidate -> {
            assertThat(candidate.deliveryId()).isEqualTo(id);
            assertThat(candidate.attempts()).isEqualTo(3); // the ledger count, layer 2 (ADR-010 section 4)
        });
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            store().markDue(id);
            return null;
        });
        UUID finalToken = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository().claimSend(id, finalToken, base.plusSeconds(300));
            return null;
        });
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().deadLetter(id, finalToken, OutcomeClass.TRANSIENT))).isTrue();

        assertThat(state(id)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().countAttempts(id))).isEqualTo(3); // history kept, never rewritten
    }

    @Test
    void theClaimRunsLockCheckThenStateInOneTransaction() {
        Instant base = Instant.parse("2026-10-09T13:00:00Z");
        // no blocker for this key: the claim sets SENDING with a fresh token and claimed_at
        UUID free = newDelivery(DeliveryTestDatabase.TENANT_A, "order-free", 5L, base);
        UUID freeToken = UUID.randomUUID();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|order-free", free, freeToken, base.plusSeconds(1))))
                .isEqualTo(Outcome.CLAIMED);
        Map<String, Object> sentRow = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> jdbc.sql("select state, claim_token, claimed_at from public.delivery"
                                + " where id = :id::uuid").params(Map.of("id", free.toString()))
                        .query((RowMapper<Map<String, Object>>) (rs, n) -> Map.of(
                                "state", rs.getString(1),
                                "token", String.valueOf(rs.getObject(2)),
                                "claimed", rs.getObject(3) != null)).single());
        assertThat(sentRow).containsEntry("state", "SENDING")
                .containsEntry("token", freeToken.toString())
                .containsEntry("claimed", true);
        assertThat(eventCount(free, DeliveryEventType.CLAIM)).isEqualTo(1);

        // an earlier non-final delivery to the same target and key: the claim evaluates the hold -> HELD
        UUID earlier = newDelivery(DeliveryTestDatabase.TENANT_A, "order-held", 1L, base);
        UUID held = newDelivery(DeliveryTestDatabase.TENANT_A, "order-held", 2L, base);
        UUID earlierToken = UUID.randomUUID();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|order-held", earlier, earlierToken, base.plusSeconds(2))))
                .isEqualTo(Outcome.CLAIMED);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|order-held", held, UUID.randomUUID(), base.plusSeconds(3))))
                .isEqualTo(Outcome.HELD);
        assertThat(state(held)).isEqualTo(DeliveryState.HELD);
        assertThat(eventCount(held, DeliveryEventType.HELD)).isEqualTo(1);

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|order-free", free, UUID.randomUUID(), base.plusSeconds(4))))
                .isEqualTo(Outcome.NO_EFFECT); // already claimed - a duplicate record acknowledges with no effect
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|order-held", held, UUID.randomUUID(), base.plusSeconds(5))))
                .isEqualTo(Outcome.NO_EFFECT); // HELD is not re-claimable; release is a separate transition

        // the blocker becomes final and the held delivery is released (ADR-010 section 6, HELD -> DUE)
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().markDelivered(earlier, earlierToken, base.plusSeconds(6)))).isTrue();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().release(held, base.plusSeconds(7)))).isTrue();
        assertThat(state(held)).isEqualTo(DeliveryState.DUE);
        assertThat(eventCount(held, DeliveryEventType.RELEASE)).isEqualTo(1);
    }

    @Test
    void aReDrivenDeliveryIgnoresTheOldClaimsOutcomeButKeepsItsAttempt() {
        Instant base = Instant.parse("2026-10-09T14:00:00Z");
        UUID id = newDelivery(DeliveryTestDatabase.TENANT_A, "stale-key", 1L, base);
        UUID staleToken = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|stale-key", id, staleToken, base.plusSeconds(1)));

        // the stale-claim path (lane B's trigger calls these store methods): RETRY_WAIT -> DUE -> re-claim
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().scheduleRetry(id, staleToken, base.plusSeconds(120)))).isTrue();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            store().lockDue(base.plusSeconds(121), 10);
            store().markDue(id);
            return null;
        });
        UUID freshToken = UUID.randomUUID();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim("t|stale-key", id, freshToken, base.plusSeconds(122))))
                .isEqualTo(Outcome.CLAIMED);

        // the first attempt's late outcome: its attempt row is written, LATE_OUTCOME recorded, state unchanged
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository()
                .recordAttempt(id, 2, OutcomeClass.AMBIGUOUS, base.plusSeconds(5), base.plusSeconds(130),
                        "the slow original call answered late"));
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().scheduleRetry(id, staleToken, base.plusSeconds(300)))).isFalse();
        assertThat(state(id)).isEqualTo(DeliveryState.SENDING);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().nextAttemptAt(id))).isEmpty();
        assertThat(eventCount(id, DeliveryEventType.LATE_OUTCOME)).isEqualTo(1);
        assertThat(eventCount(id, DeliveryEventType.RETRY)).isEqualTo(1); // only the fresh-era retry
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().countAttempts(id))).isEqualTo(1);
    }

    @Test
    void twoConcurrentClaimsOfOneDeliveryExactlyOneWins() throws Exception {
        Instant base = Instant.parse("2026-10-09T15:00:00Z");
        UUID id = newDelivery(DeliveryTestDatabase.TENANT_A, "race-key", 1L, base);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            Future<Outcome> one = executor.submit(() -> {
                bothReady.countDown();
                bothReady.await();
                return tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                        () -> claimService().claim("t|race-key", id, UUID.randomUUID(), base.plusSeconds(1)));
            });
            Future<Outcome> two = executor.submit(() -> {
                bothReady.countDown();
                bothReady.await();
                return tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                        () -> claimService().claim("t|race-key", id, UUID.randomUUID(), base.plusSeconds(1)));
            });
            List<Outcome> outcomes = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CLAIMED, Outcome.NO_EFFECT);
        } finally {
            executor.shutdownNow();
        }
        assertThat(state(id)).isEqualTo(DeliveryState.SENDING);
    }
}
