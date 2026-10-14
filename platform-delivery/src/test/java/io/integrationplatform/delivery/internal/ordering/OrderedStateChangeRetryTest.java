package io.integrationplatform.delivery.internal.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.ordering.OrderingKey;
import io.integrationplatform.delivery.port.OrderedStateChangeRetryExhaustedException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The 40001/40P01 in-place retry of the ONE lock order, T-197 (E9-F2-S1 "A serialization failure or deadlock is
 * retried in place"), as a JVM-level test of the retry loop itself: three attempts in total, full-jitter backoff
 * never longer than the doubled first band, no attempt row written by the helper (that is the caller's business -
 * the loop writes nothing), any other failure propagates untouched, and a change running inside a caller's open
 * transaction is never retried (its failure collapses the caller transaction). The delivery-row scenarios live in
 * {@link OrderedStateChangeContainerTest} on the real PostgreSQL.
 */
class OrderedStateChangeRetryTest {

    /** The support with its attempts scripted per attempt number: a value is the body's result, an exception thrown. */
    private static final class ScriptedChange extends OrderedStateChangeSupport {

        final IntFunction<Object> script;
        final List<Long> waits = new ArrayList<>();
        final List<List<UUID>> lockedIds = new ArrayList<>();
        final List<String> lockedKeys = new ArrayList<>();
        boolean retryable = true;
        int attempts;

        ScriptedChange(IntFunction<Object> script) {
            this.script = script;
        }

        @Override
        protected boolean canRetry() {
            return retryable;
        }

        @Override
        protected void waitBeforeRetry(long millis) {
            waits.add(millis); // recorded, never slept - the loop's timing is what this test proves
        }

        @SuppressWarnings("unchecked")
        @Override
        protected <T> T attempt(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending,
                java.util.function.Supplier<T> body) {
            attempts++;
            lockedKeys.add(orderingKey);
            lockedIds.add(deliveryIdsAscending);
            Object step = script.apply(attempts);
            if (step instanceof Throwable failure) {
                throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
            }
            return (T) step;
        }
    }

    @Test
    void theBodyRunsExactlyOnceAndItsResultComesBack() {
        UUID messageId = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ScriptedChange change = new ScriptedChange(attempt -> "ok:" + attempt);

        assertThat(change.orderedRun("key-one", messageId, List.of(delivery), () -> "unused")).isEqualTo("ok:1");
        assertThat(change.attempts).isEqualTo(1);
        assertThat(change.waits).isEmpty();
        assertThat(change.lockedIds).containsExactly(List.of(delivery));
        assertThat(change.lockedKeys).containsExactly("key-one");
    }

    @Test
    void serializationAndDeadlockFailuresAreRetriedInPlaceUntilTheStateChangeLands() {
        UUID messageId = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ScriptedChange change = new ScriptedChange(attempt -> {
            if (attempt < 3) {
                throw attempt == 1
                        ? new CannotAcquireLockException("40P01 deadlock on attempt 1")
                        : new CannotAcquireLockException("40001 on attempt 2");
            }
            return "landed";
        });

        Object landed = change.orderedRun("key", messageId, List.of(delivery),
                (java.util.function.Supplier<Object>) () -> null);
        assertThat(landed).isEqualTo("landed");
        assertThat(change.attempts).isEqualTo(3);
        assertThat(change.waits).hasSize(2);
        assertThat(change.waits.get(0)).isBetween(0L, OrderedStateChangeSupport.FIRST_WAIT_MILLIS); // full jitter of 100 ms
        assertThat(change.waits.get(1)).isBetween(0L, OrderedStateChangeSupport.FIRST_WAIT_MILLIS * 2);
        assertThat(change.lockedIds).containsExactly(List.of(delivery), List.of(delivery), List.of(delivery));
    }

    @Test
    void theThirdConsecutiveFailureThrowsTheExhaustedExceptionWithTheLastCause() {
        UUID messageId = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ScriptedChange change = new ScriptedChange(attempt -> {
            throw new CannotAcquireLockException("40001, attempt " + attempt);
        });

        assertThatThrownBy(() -> change.orderedRun("key", messageId, List.of(delivery), () -> null))
                .isInstanceOf(OrderedStateChangeRetryExhaustedException.class)
                .hasMessageContaining("consecutive")
                .hasRootCauseMessage("40001, attempt 3");
        assertThat(change.attempts).isEqualTo(3); // the budget, never more (ADR-010 section 7)
        assertThat(change.waits).hasSize(2);      // jittered backoff between them
    }

    @Test
    void anyOtherFailurePropagatesOnItsFirstAttemptWithoutARetry() {
        UUID messageId = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ScriptedChange change = new ScriptedChange(attempt -> {
            throw new IllegalStateException("a bad statement is not a lost attempt");
        });

        assertThatThrownBy(() -> change.orderedRun("key", messageId, List.of(delivery), () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a bad statement is not a lost attempt");
        assertThat(change.attempts).isEqualTo(1);
        assertThat(change.waits).isEmpty();
    }

    @Test
    void aChangeInsideACallerTransactionIsNeverRetried() {
        UUID messageId = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ScriptedChange change = new ScriptedChange(attempt -> {
            throw new CannotAcquireLockException("40001");
        });
        change.retryable = false; // the joined-transaction shape: the caller's transaction collapses

        assertThatThrownBy(() -> change.orderedRun("key", messageId, List.of(delivery), () -> null))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessage("40001"); // the original failure, not the exhausted wrapper
        assertThat(change.attempts).isEqualTo(1);
        assertThat(change.waits).isEmpty();
    }

    @Test
    void theDeliveriesAreAlwaysLockedInAscendingIdOrderWhateverOrderTheCallerNamesThem() {
        UUID messageId = UUID.randomUUID();
        UUID one = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID two = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID three = UUID.fromString("10000000-0000-0000-0000-000000000003");
        ScriptedChange change = new ScriptedChange(attempt -> "ok");

        change.orderedRun("key", messageId, List.of(three, one, two, one), () -> null);
        assertThat(change.lockedIds.get(0)).containsExactly(one, two, three); // one order, duplicates dropped
    }

    @Test
    void theOrderingScopeDerivesOneStableHexRecordKeyPerTriple() {
        UUID tenant = UUID.randomUUID();
        UUID another = UUID.randomUUID();
        UUID interfaceId = UUID.randomUUID();
        String key = OrderingKey.recordKeyFor(tenant, interfaceId, "B-77");

        assertThat(key).hasSize(64).matches("[0-9a-f]{64}"); // SHA-256 hex (ADR-011 Decision 7)
        assertThat(key).isEqualTo(OrderingKey.recordKeyFor(tenant, interfaceId, "B-77")); // stable
        assertThat(key).isNotEqualTo(OrderingKey.recordKeyFor(another, interfaceId, "B-77"));
        assertThat(key).isNotEqualTo(OrderingKey.recordKeyFor(tenant, interfaceId, "B-78"));
        assertThatThrownBy(() -> OrderingKey.recordKeyFor(tenant, interfaceId, null))
                .isInstanceOf(NullPointerException.class); // an ordering key needs its business key
    }

    @Test
    void transactionSynchronizationManagerIsAvailableForTheJoinedShapeCheck() {
        // the contract the JdbcOrderedStateChange relies on: a thread with no transaction is retryable,
        // a thread with an open caller transaction is flagged by TransactionSynchronizationManager
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}