package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.poolisolation.BoundedPoolTransactionManager;
import io.integrationplatform.routing.poolisolation.CountingTransactionManager;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T175 (T71 for DB): the target database insert must not hold a pooled application connection. The platform reads
 * (settings, credential, mapped rows, the F3 approved-table check) each run in their own short transaction that is
 * over before the destination is dialled; the insert itself runs with no transaction open.
 */
class DbPoolIsolationTest {

    private static final DbSettings SETTINGS = new DbSettings("db.example", 5432, "orders", "restricted", "orders_in");

    private final Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
            false);
    private final List<String> order = new ArrayList<>();

    private DestinationGuard guard() {
        return new DestinationGuard(AllowList.of(List.of("db.example")),
                name -> List.of(InetAddress.getLoopbackAddress()), List.of("db.example"), List.of(5432));
    }

    private DbOutbound outbound(PlatformTransactionManager pool, AtomicInteger openDuringResolve) {
        DbMessage message = new DbMessage(SETTINGS, "restricted", "pw".toCharArray(), "orders_in", List.of("awb"),
                List.of(List.of("A1")));
        return s -> {
            order.add("resolve");
            if (pool instanceof CountingTransactionManager counting) {
                openDuringResolve.set(counting.open());
            }
            return Optional.of(message);
        };
    }

    private DbTargetCall compose(PlatformTransactionManager pool, DbOutbound outbound, ApprovedTables approved,
            DbSender sender) {
        return DbAdapterConfiguration.compose(approved, outbound, sender, guard(), List.of(5432), pool,
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @Test
    void noTransactionIsOpenWhileTheTargetInsertRuns() {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger openDuringResolve = new AtomicInteger(-1);
        AtomicInteger openDuringApproval = new AtomicInteger(-1);
        AtomicInteger openDuringInsert = new AtomicInteger(-1);

        compose(pool, outbound(pool, openDuringResolve), (endpoint, table) -> {
            order.add("approved");
            openDuringApproval.set(pool.open());
            return true;
        }, (message, deliveryId, address) -> {
            order.add("insert");
            openDuringInsert.set(pool.open());
        }).call(send);

        assertThat(order).as("F3 approved-table check runs before the call").containsExactly("resolve", "approved",
                "insert");
        assertThat(openDuringResolve.get()).as("the settings read ran in its own transaction").isEqualTo(1);
        assertThat(openDuringApproval.get()).as("the approval read ran in its own transaction").isEqualTo(1);
        assertThat(openDuringInsert.get()).as("pooled connections held while the target insert runs").isZero();
        assertThat(pool.begun()).as("two short transactions").isEqualTo(2);
        assertThat(pool.open()).as("nothing leaks").isZero();
    }

    @Test
    void otherWorkProceedsOnAPoolOfOneWhileASlowInsertIsInFlight() throws Exception {
        BoundedPoolTransactionManager pool = new BoundedPoolTransactionManager(1, 500);
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DbTargetCall call = compose(pool, outbound(pool, new AtomicInteger()), (endpoint, table) -> true,
                (message, deliveryId, address) -> {
                    inFlight.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread slow = Thread.ofPlatform().start(() -> {
            try {
                call.call(send);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).as("the slow insert is in flight").isTrue();
            // intake and other deliveries need the one connection: it must be free now
            new TransactionTemplate(pool).executeWithoutResult(status -> { });
            assertThat(pool.available()).isEqualTo(1);
        } finally {
            release.countDown();
            slow.join();
        }
        assertThat(failure.get()).isNull();
    }

    @Test
    void anUnapprovedTableIsRefusedPermanentBeforeAnyInsert() {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger inserts = new AtomicInteger();
        DbTargetCall call = compose(pool, outbound(pool, new AtomicInteger()), (endpoint, table) -> false,
                (message, deliveryId, address) -> inserts.incrementAndGet());

        assertThatThrownBy(() -> call.call(send)).isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(inserts.get()).isZero();
        assertThat(pool.open()).isZero();
    }

    @Test
    void aFailedApprovalLookupIsTransientAndNeverReadAsApproved() {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger inserts = new AtomicInteger();
        DbTargetCall call = compose(pool, outbound(pool, new AtomicInteger()), (endpoint, table) -> {
            throw new IllegalStateException("platform database down");
        }, (message, deliveryId, address) -> inserts.incrementAndGet());

        assertThatThrownBy(() -> call.call(send)).isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass()).isEqualTo(OutcomeClass.TRANSIENT);
        assertThat(inserts.get()).isZero();
        assertThat(pool.open()).isZero();
    }
}
