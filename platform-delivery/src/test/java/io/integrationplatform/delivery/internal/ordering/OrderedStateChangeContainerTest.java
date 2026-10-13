package io.integrationplatform.delivery.internal.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.OrderedStateChangeRetryExhaustedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one lock order on the real PostgreSQL (T-197; E9-F2-S1 "Every state change takes the key lock first, in one
 * row order" and "A serialization failure or deadlock is retried in place"): transactions on one key that lock the
 * same delivery rows in opposite row order both finish under the helper - serialized by the advisory key lock
 * before any row lock - where bare transactions of the opposite orders deadlock; a blank key (ordering
 * {@code NONE}) never takes the advisory lock; a real 40001 is retried in place and lands exactly once with no
 * attempt row; the third consecutive failure exhausts (the E8-F1-S2 consumer pause applies).
 */
@Tag("container")
class OrderedStateChangeContainerTest {

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

    private JdbcOrderedStateChange ordered() {
        return ordered(new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                pool)));
    }

    private JdbcOrderedStateChange ordered(TransactionTemplate transactions) {
        return new JdbcOrderedStateChange(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                transactions);
    }

    private UUID newDelivery(String businessKey, long keySeq, Instant at) {
        UUID id = UUID.randomUUID();
        UUID tenant = DeliveryTestDatabase.TENANT_A;
        tx.inTenantTx(tenant, () -> new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant))
                .create(id, DeliveryTestDatabase.INTERFACE_ID, businessKey, keySeq,
                        DeliveryTestDatabase.TARGET_ID, at));
        return id;
    }

    /**
     * The control premise of E9-F2-S1: WITHOUT the helper, two transactions on one key that lock the same rows on
     * the opposite orders deadlock - PostgreSQL aborts exactly one of them (40P01). This is what the helper makes
     * impossible in the next test.
     */
    @Test
    void twoBareTransactionsOnOneKeyInOppositeRowOrdersDeadlockWithoutTheHelper() throws Exception {
        UUID earlier = newDelivery("oo-key", 1L, DAY);
        UUID later = newDelivery("oo-key", 2L, DAY);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (
                Connection first = db.superuser();
                Connection second = db.superuser()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            CountDownLatch firstLocked = new CountDownLatch(1);
            CountDownLatch secondLocked = new CountDownLatch(1);
            Future<?> a = executor.submit(() -> {
                updateRow(first, earlier); // transaction A holds the earlier row
                firstLocked.countDown();
                secondLocked.await(30, TimeUnit.SECONDS);
                updateRow(first, later); // A wants the row B holds - the circular wait begins
                return null;
            });
            Future<?> b = executor.submit(() -> {
                updateRow(second, later); // transaction B holds the later row
                secondLocked.countDown();
                firstLocked.await(30, TimeUnit.SECONDS);
                updateRow(second, earlier); // B wants the row A holds - PG's detector ends exactly one
                return null;
            });
            List<Object> outcomes;
            try {
                outcomes = List.of(finish(a), finish(b));
            } finally {
                executor.shutdownNow();
            }
            long thrown = outcomes.stream().filter(ExceptionWithMessage.class::isInstance).count();
            assertThat(thrown)
                    .as("the control premise holds: opposite row orders on one key deadlocked one transaction")
                    .isEqualTo(1);
        }
    }

    /** {@code get} keeping the thrown status as the result value, so both futures can be compared. */
    private static Object finish(Future<?> future) {
        try {
            future.get(45, TimeUnit.SECONDS);
            return "committed";
        } catch (Exception e) {
            return new ExceptionWithMessage(e);
        }
    }

    private static final class ExceptionWithMessage extends RuntimeException {
        ExceptionWithMessage(Throwable cause) {
            super(cause);
        }
    }

    private static void updateRow(Connection connection, UUID deliveryId) {
        try (PreparedStatement update = connection.prepareStatement(
                "update public.delivery set updated_at = now() where id = ?::uuid")) {
            update.setString(1, deliveryId.toString());
            update.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The helper: the SAME two opposite row orders on one key both finish and lose no update. */
    @Test
    void twoOppositeRowOrderChangesOnOneKeyBothFinishAndLoseNoUpdate() throws Exception {
        UUID earlier = newDelivery("oo-key", 1L, DAY);
        UUID later = newDelivery("oo-key", 2L, DAY);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            Future<Boolean> one = executor.submit(() -> {
                bothReady.countDown();
                bothReady.await(30, TimeUnit.SECONDS);
                return ordered().run("oo-key", null, List.of(later, earlier), // deliberately opposite to the other
                        (Supplier<Boolean>) () -> repository().hold(earlier, DAY.plusSeconds(1)));
            });
            Future<Boolean> two = executor.submit(() -> {
                bothReady.countDown();
                bothReady.await(30, TimeUnit.SECONDS);
                return ordered().run("oo-key", null, List.of(earlier, later),
                        (Supplier<Boolean>) () -> repository().hold(later, DAY.plusSeconds(1)));
            });
            assertThat(one.get(45, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(45, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(state(earlier)).isEqualTo(DeliveryState.HELD); // both landed, 0 lost updates
        assertThat(state(later)).isEqualTo(DeliveryState.HELD);
        assertThat(eventCount(earlier, DeliveryEventType.HELD)).isEqualTo(1);
        assertThat(eventCount(later, DeliveryEventType.HELD)).isEqualTo(1);
    }

    @Test
    void aSerializationFailureIsRetriedInPlaceAndLandsExactlyOnceWithoutAnAttemptRow() throws Exception {
        UUID id = newDelivery("40001-key", 1L, DAY);
        TransactionTemplate repeatableRead = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool));
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        JdbcOrderedStateChange change = (JdbcOrderedStateChange) ordered(repeatableRead);

        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch controlCommitted = new CountDownLatch(1);
        AtomicInteger bodyRuns = new AtomicInteger();
        try (Connection control = db.superuser()) {
            control.setAutoCommit(false);
            updateRow(control, id); // holds the row - the first attempt's update waits behind it

            ExecutorService executor = Executors.newFixedThreadPool(1);
            try {
                // no delivery ids up front: the BODY's hold is the update that loses the race (the helper's
                // row-lock phase would wait before the body ever ran and the latches below would never fire)
                Future<Boolean> attempt = executor.submit(
                        () -> change.run("40001-key", null, List.of(), (Supplier<Boolean>) () -> {
                            if (bodyRuns.incrementAndGet() == 1) { // the losing attempt: wait for the control commit
                                bodyStarted.countDown();
                                try {
                                    assertThat(controlCommitted.await(30, TimeUnit.SECONDS)).isTrue();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }
                            return repository().hold(id, DAY.plusSeconds(1));
                        }));
                // the attempt's transaction is now open behind the control's row lock; committing the control
                // concurrently is what makes the attempt's REPEATABLE READ update a real 40001
                assertThat(bodyStarted.await(30, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                control.commit();
                controlCommitted.countDown();
                assertThat(attempt.get(45, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
        assertThat(state(id)).isEqualTo(DeliveryState.HELD); // landed exactly once (the lost attempt rolled back)
        assertThat(eventCount(id, DeliveryEventType.HELD)).isEqualTo(1);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countAttempts(id)))
                .as("no attempt row is written for a 40001 retry (ADR-010 section 7)").isZero();
        assertThat(bodyRuns.get()).isEqualTo(2); // the losing attempt and the one that landed
    }

    @Test
    void theThirdConsecutiveFailureExhaustsTheBudgetWithoutAnAttemptRow() {
        UUID id = newDelivery("exhaust-key", 1L, DAY);
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> ordered().run("exhaust-key", null, List.of(id), (Supplier<Boolean>) () -> {
            attempts.incrementAndGet();
            throw new DeadlockLoserDataAccessException("40P01, every attempt loses", null);
        })).isInstanceOf(OrderedStateChangeRetryExhaustedException.class) // the E8-F1-S2 consumer pause applies
                .hasRootCauseMessage("40P01, every attempt loses");
        assertThat(attempts.get()).isEqualTo(3); // three attempts in total, never more
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countAttempts(id))).isZero();
        assertThat(state(id)).isEqualTo(DeliveryState.PENDING); // nothing moved
    }

    // ------------------------------------------------------------- the advisory key lock itself

    @Test
    void theOrderingKeyLockIsTakenBeforeAnyRowAndSerializesOneKey() throws Exception {
        UUID id = newDelivery("serial-key", 1L, DAY);
        CountDownLatch bodyDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try (Connection control = db.superuser()) {
            control.setAutoCommit(false);
            try (PreparedStatement lock = control.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "serial-key");
                lock.executeQuery().next();
            }
            Future<String> change = executor.submit(() -> ordered().run("serial-key", null, List.of(id),
                    (Supplier<String>) () -> {
                        bodyDone.countDown();
                        return "body-ran";
                    }));
            Thread.sleep(600); // a change whose FIRST lock is the key lock cannot finish while the control holds it
            assertThat(bodyDone.getCount())
                    .as("the key advisory lock is the first lock: the change waits for the control session")
                    .isEqualTo(1);
            control.commit();
            assertThat(change.get(45, TimeUnit.SECONDS)).isEqualTo("body-ran");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anOrderingNoneChangeSkipsTheAdvisoryKeyLockAltogether() throws Exception {
        UUID id = newDelivery("none-key", 0L, DAY);
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try (Connection control = db.superuser()) {
            control.setAutoCommit(false);
            try (PreparedStatement lock = control.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "none-key");
                lock.executeQuery().next();
            }
            // no caller transaction either: the helper's own attempt must still run, and with a blank key it
            // must NOT wait for the control session's advisory lock (ordering NONE is never held or locked)
            Future<Boolean> change = executor.submit(() -> ordered().run("  ", null, List.of(id),
                    (Supplier<Boolean>) () -> repository().hold(id, DAY.plusSeconds(1))));
            assertThat(change.get(10, TimeUnit.SECONDS)).isTrue(); // a key-lock taker would block past this timeout
            control.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(state(id)).isEqualTo(DeliveryState.HELD);
    }

    // ------------------------------------------------------------- small helpers

    private DeliveryState state(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)).orElseThrow();
    }

    private long eventCount(UUID id, DeliveryEventType type) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countEvents(id, type));
    }
}