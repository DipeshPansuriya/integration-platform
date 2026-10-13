package io.integrationplatform.delivery.e9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.DeliveryClaimService.Outcome;
import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.ordering.JdbcOrderedStateChange;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-165 (E9-F2-S1, per ADR fix m-817272 B3/B4; ADR-011 Decision 6): the per-target same-key hold and the release
 * IN THE SAME TRANSACTION as the predecessor's final state - a delivered or dead-lettered predecessor releases
 * the held successor, writes its {@code platform.delivery.due} record (so it is sent) and rolls back with them;
 * a further held successor stays held behind a merely non-final one; other targets and none-ordering are never
 * held. The unrouted-message part of the hold rule (an earlier message still ACCEPTED) is clause 1 of the hold
 * check - it joins with the E8 wave-1 message table (the brief's gate) and stays gated.
 */
@Tag("container")
class DeliveryHoldReleaseContainerTest {

    private static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS);

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

    /** The bare E2 claim (its own 2-ctor shape); the hold/release tests use it to isolate the release behavior. */
    private DeliveryClaimService bareClaimService() {
        return new DeliveryClaimService(repository());
    }

    /** The claim wired through the ONE lock-order helper (the E9 adoption the same shape must prove). */
    private DeliveryClaimService claimService() {
        return new DeliveryClaimService(repository(),
                new JdbcOrderedStateChange(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                        new DataSourceTransactionManager(pool)));
    }

    private UUID newDelivery(UUID tenant, UUID target, String businessKey, Long keySeq, Instant at) {
        UUID id = UUID.randomUUID();
        tx.inTenantTx(tenant, () -> new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant))
                .create(id, DeliveryTestDatabase.INTERFACE_ID, businessKey, keySeq, target, at));
        return id;
    }

    /** Claims (SENDING) and returns the claim token the retry and the final state must carry. */
    private UUID sending(UUID id, Instant at) {
        UUID token = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().claimSend(id, token, at));
        return token;
    }

    private void toRetryWait(UUID id, UUID token, Instant next, Instant at) {
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().scheduleRetry(id, token, next, at.plusSeconds(1)));
    }

    /** Re-drives the RETRY_WAIT delivery to DUE and re-claims it: the fresh token its final state requires. */
    private UUID reClaim(UUID id, Instant at) {
        UUID token = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            var repository = repository();
            repository.redrive(id, at);
            repository.claimSend(id, token, at);
        });
        return token;
    }

    private DeliveryState state(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)).orElseThrow();
    }

    private long eventCount(UUID id, DeliveryEventType type) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countEvents(id, type));
    }

    private List<Map<String, Object>> dueOutboxRows(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select topic, record_key, key_bucket, published_at from public.outbox"
                                + " where aggregate_id = :aggregate_id::uuid")
                .param("aggregate_id", id.toString())
                .query((rs, n) -> Map.<String, Object>of("topic", rs.getString(1), "record_key", rs.getString(2),
                        "key_bucket", rs.getInt(3), "published_at",
                        rs.getObject(4) == null ? "null" : "published"))
                .list());
    }

    @Test
    void thePredecessorBecomingDeliveredReleasesTheHeldDeliveryInTheSameTransactionAndSendsIt() {
        Instant base = DAY.plus(1, ChronoUnit.HOURS);
        UUID predecessor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "release-key", 1L, base);
        UUID successor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "release-key", 2L, base);
        UUID token = sending(predecessor, base); // SENDING behind claim token
        toRetryWait(predecessor, token, base.plusSeconds(60), base); // update 1 is in RETRY_WAIT
        UUID finalToken = reClaim(predecessor, base.plusSeconds(61)); // the fresh token the final state requires
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("release-key", successor, UUID.randomUUID(), base.plusSeconds(2))))
                .isEqualTo(Outcome.HELD); // update 2 is held (E2's claim order)
        assertThat(dueOutboxRows(successor)).isEmpty();

        // the predecessor's final state releases the successor IN THE SAME TRANSACTION (ADR-011 Decision 6, B4)
        boolean delivered = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().markDelivered(predecessor, finalToken, base.plusSeconds(3)));
        assertThat(delivered).isTrue();
        assertThat(state(predecessor)).isEqualTo(DeliveryState.DELIVERED);
        assertThat(state(successor))
                .as("the held successor is released by the same transaction, not by a later one")
                .isEqualTo(DeliveryState.DUE);
        assertThat(eventCount(successor, DeliveryEventType.RELEASE)).isEqualTo(1);
        // and it is sent: the release's same transaction appends the delivery's due record (ADR-014 stage 1)
        List<Map<String, Object>> dueRecord = dueOutboxRows(successor);
        assertThat(dueRecord).hasSize(1).first().satisfies(row -> assertThat(row)
                .containsEntry("topic", "platform.delivery.due")
                .containsEntry("record_key", "delivery:" + successor)
                .containsEntry("published_at", "null"));
    }

    @Test
    void thePredecessorBecomingDeadLetteredReleasesTheHeldDeliveryInTheSameTransaction() {
        Instant base = DAY.plus(2, ChronoUnit.HOURS);
        UUID predecessor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "dead-release-key", 1L, base);
        UUID successor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "dead-release-key", 2L, base);
        UUID token = sending(predecessor, base);
        toRetryWait(predecessor, token, base.plusSeconds(60), base);
        UUID finalToken = reClaim(predecessor, base.plusSeconds(61)); // the fresh token the final state requires
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("dead-release-key", successor, UUID.randomUUID(), base.plusSeconds(2))))
                .isEqualTo(Outcome.HELD);

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().deadLetter(predecessor, finalToken, OutcomeClass.AMBIGUOUS, base.plusSeconds(3))))
                .isTrue();
        assertThat(state(successor)).isEqualTo(DeliveryState.DUE); // a dead-lettered predecessor releases too
        assertThat(eventCount(successor, DeliveryEventType.RELEASE)).isEqualTo(1);
        assertThat(dueOutboxRows(successor)).hasSize(1);
    }

    @Test
    void aRolledBackFinalStateLeavesTheSuccessorHeldAndAppendsNoDueRecord() {
        // the release is in the SAME transaction: roll it back and nothing of it may remain
        Instant base = DAY.plus(3, ChronoUnit.HOURS);
        UUID predecessor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "rollback-key", 1L, base);
        UUID successor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "rollback-key", 2L, base);
        UUID token = sending(predecessor, base);
        toRetryWait(predecessor, token, base.plusSeconds(60), base);
        UUID finalToken = reClaim(predecessor, base.plusSeconds(61)); // the fresh token the final state requires
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("rollback-key", successor, UUID.randomUUID(), base.plusSeconds(2))))
                .isEqualTo(Outcome.HELD);

        TransactionTemplate rollbackTransaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
        assertThatThrownBy(() -> rollbackTransaction.execute(status -> {
            repository().stampTenant(); // the E9 helper shape: a transaction the code opened is stamped by us
            repository().markDelivered(predecessor, finalToken, base.plusSeconds(3));
            status.setRollbackOnly(); // the outcome failed downstream of the delivery's own store
            throw new IllegalStateException("the bulkhead could not hand the send over");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(state(predecessor)).isEqualTo(DeliveryState.SENDING); // the final state itself rolled back
        assertThat(state(successor)).isEqualTo(DeliveryState.HELD);     // nothing released it
        assertThat(eventCount(successor, DeliveryEventType.RELEASE)).isZero();
        assertThat(dueOutboxRows(successor)).isEmpty();
    }

    @Test
    void aHeldDeliveryBehindARemainingBlockerStaysHeld() {
        Instant base = DAY.plus(4, ChronoUnit.HOURS);
        UUID first = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "chain-key", 1L, base);
        UUID second = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "chain-key", 2L, base);
        UUID third = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "chain-key", 3L, base);
        UUID token = sending(first, base);
        toRetryWait(first, token, base.plusSeconds(60), base);
        UUID finalToken = reClaim(first, base.plusSeconds(61)); // the fresh token the final state requires
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("chain-key", second, UUID.randomUUID(), base.plusSeconds(2))))
                .isEqualTo(Outcome.HELD);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("chain-key", third, UUID.randomUUID(), base.plusSeconds(3))))
                .isEqualTo(Outcome.HELD);

        // release the first chain: the earliest held becomes due, the later HELD has a non-final predecessor left
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository().markDelivered(first, finalToken, base.plusSeconds(4)))).isTrue();
        assertThat(state(second)).isEqualTo(DeliveryState.DUE);
        assertThat(state(third))
                .as("the next held delivery stays held until its own predecessor is final")
                .isEqualTo(DeliveryState.HELD);
        assertThat(eventCount(third, DeliveryEventType.RELEASE)).isZero();
        assertThat(dueOutboxRows(third)).isEmpty();
    }

    @Test
    void otherTargetsAreNeverHeldByRetryingPredecessorOfAnotherTarget() {
        Instant base = DAY.plus(5, ChronoUnit.HOURS);
        UUID targetB = UUID.fromString("22222222-0000-4000-8000-0000000000b2");
        UUID predecessor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "scope-key", 1L, base);
        UUID sameTarget = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "scope-key", 2L, base);
        UUID otherTarget = newDelivery(DeliveryTestDatabase.TENANT_A, targetB, "scope-key", 3L, base);
        UUID token = sending(predecessor, base);
        toRetryWait(predecessor, token, base.plusSeconds(60), base);

        // update 2 goes to both targets: Target B's delivery is sent at once, only Target A's is held
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("scope-key|B", otherTarget, UUID.randomUUID(), base.plusSeconds(2))))
                .isEqualTo(Outcome.CLAIMED);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> bareClaimService().claim("scope-key", sameTarget, UUID.randomUUID(), base.plusSeconds(3))))
                .isEqualTo(Outcome.HELD);
        assertThat(state(otherTarget)).isEqualTo(DeliveryState.SENDING);
        assertThat(state(sameTarget)).isEqualTo(DeliveryState.HELD);
    }

    @Test
    void anOrderingNoneDeliveryIsNeverHeld() {
        Instant base = DAY.plus(6, ChronoUnit.HOURS);
        UUID predecessor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "none-key", 1L, base);
        UUID follower = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "none-key", null, base); // key_seq NULL - interface ordering is NONE
        UUID token = sending(predecessor, base);
        toRetryWait(predecessor, token, base.plusSeconds(60), base);

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> claimService().claim(null, follower, UUID.randomUUID(), base.plusSeconds(2))))
                .as("ordering NONE: sent without a hold")
                .isEqualTo(Outcome.CLAIMED);
        assertThat(state(follower)).isEqualTo(DeliveryState.SENDING);
    }

    @Test
    void theClaimThroughTheHelperRunsInItsOwnTransactionWhenTheCallerOpensNone() {
        // the E9 wiring: the claim service carries the helper, and the caller opened NO transaction - the
        // helper's own attempt supplies the transaction and the tenant stamp (R11 stays satisfied)
        Instant base = DAY.plus(7, ChronoUnit.HOURS);
        UUID predecessor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "helper-claim-key", 1L, base);
        UUID successor = newDelivery(DeliveryTestDatabase.TENANT_A, DeliveryTestDatabase.TARGET_ID,
                "helper-claim-key", 2L, base);
        UUID token = sending(predecessor, base);
        toRetryWait(predecessor, token, base.plusSeconds(60), base);

        // NO inTenantTx here: the helper's own-transaction shape must be indistinguishable in outcome
        assertThat(claimService().claim("helper-claim-key", successor, UUID.randomUUID(), base.plusSeconds(2)))
                .isEqualTo(Outcome.HELD);
        assertThat(state(successor)).isEqualTo(DeliveryState.HELD);
        assertThat(eventCount(successor, DeliveryEventType.HELD)).isEqualTo(1);
    }
}