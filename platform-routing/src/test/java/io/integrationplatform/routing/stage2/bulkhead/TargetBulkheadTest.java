package io.integrationplatform.routing.stage2.bulkhead;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-177 (E9-F1-S4, ADR-010 section 2 "the per-target bulkhead"): one bounded, non-blocking pool per target
 * endpoint. The contract: at most {@code max_concurrent_sends} running at a time, acquisition never blocks,
 * there is NO hidden in-memory queue (the permit is the only admission - no permit means "full - defer"; a held
 * permit is a guaranteed hand-off, B19 F2), permits return on {@code release},
 * and work accepted by the pool runs on the pool's own threads, never on the caller's. The bulkhead of one
 * target is fully independent of another's (one slow target never occupies the pool another target needs).
 */
class TargetBulkheadTest {

    @Test
    @DisplayName("acquire is non-blocking up to the bound and fails fast beyond it - no queue, no wait")
    void acquireIsBoundedAndNonBlocking() {
        TargetBulkhead bulkhead = new TargetBulkhead(2, UUID.randomUUID());
        try {
            assertThat(bulkhead.tryAcquire()).isTrue();
            assertThat(bulkhead.tryAcquire()).isTrue();
            long start = System.nanoTime();
            assertThat(bulkhead.tryAcquire()).as("full pool refuses immediately").isFalse();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .as("the refusal is fast, never a queue wait").isLessThan(50);
            bulkhead.release();
            assertThat(bulkhead.tryAcquire()).as("a returned permit reopens the bound").isTrue();
        } finally {
            bulkhead.shutdown();
        }
    }

    @Test
    @DisplayName("tasks execute on the bulkhead's own threads and the in-flight bound holds under pressure")
    void tasksRunOnPoolThreadsUnderTheBound() throws Exception {
        int bound = 3;
        TargetBulkhead bulkhead = new TargetBulkhead(bound, UUID.randomUUID());
        try {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxSeen = new AtomicInteger();
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch allStarted = new CountDownLatch(bound);
            for (int i = 0; i < bound; i++) {
                assertThat(bulkhead.tryAcquire()).isTrue();
                assertThat(bulkhead.execute(() -> {
                    assertThat(Thread.currentThread().getName())
                            .as("the call leaves the partition thread only through the pool")
                            .startsWith("bulkhead-");
                    int now = running.incrementAndGet();
                    maxSeen.accumulateAndGet(now, Math::max);
                    allStarted.countDown();
                    try {
                        hold.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                    bulkhead.release();
                })).isTrue();
            }
            assertThat(allStarted.await(5, TimeUnit.SECONDS)).as("all bound tasks started").isTrue();
            assertThat(maxSeen.get()).isEqualTo(bound);
            hold.countDown();
        } finally {
            bulkhead.shutdown();
        }
    }

    @Test
    @DisplayName("B19 F2: a hand-off with a free permit is never rejected - not even while the worker that"
            + " returned it is still inside its task")
    void handOffWithAFreePermitIsNeverRejected() throws Exception {
        TargetBulkhead bulkhead = new TargetBulkhead(1, UUID.randomUUID());
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch leaveTask = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        try {
            assertThat(bulkhead.tryAcquire()).isTrue();
            assertThat(bulkhead.execute(() -> {
                // DeliveryDueDispatch's task shape: the send, then the permit returns in the task's finally -
                // and the worker is still inside the task, not yet back polling for the next hand-off
                bulkhead.release();
                released.countDown();
                await(leaveTask);
            })).isTrue();
            assertThat(released.await(5, TimeUnit.SECONDS)).as("the first send returned its permit").isTrue();

            assertThat(bulkhead.tryAcquire()).as("the permit is free again").isTrue();
            boolean handedOff = bulkhead.execute(secondRan::countDown);
            leaveTask.countDown();

            assertThat(handedOff)
                    .as("a held permit is a guaranteed hand-off - a rejection here strands the CLAIMED row"
                            + " (DeliveryDueDispatch keeps it SENDING for the reconciler)")
                    .isTrue();
            assertThat(secondRan.await(5, TimeUnit.SECONDS)).as("the accepted task runs on the pool").isTrue();
        } finally {
            leaveTask.countDown();
            bulkhead.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("two targets' bulkheads are independent - a full target A never stops target B")
    void targetsAreIsolated() {
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        TargetBulkhead a = registry.of(targetA);
        TargetBulkhead b = registry.of(targetB);
        assertThat(a).isNotSameAs(b);
        assertThat(a.tryAcquire()).isTrue();
        assertThat(a.tryAcquire()).as("A is full").isFalse();
        assertThat(b.tryAcquire()).as("B still admits - the pools are per target").isTrue();
        registry.shutdown();
    }

    @Test
    @DisplayName("the bound is the typed setting: default 4, validated 1-32 (ADR-010 section 2)")
    void settingsAreValidated() {
        assertThat(new BulkheadSettings(4).maxConcurrentSends()).isEqualTo(4);
        assertThat(new BulkheadSettings(1).maxConcurrentSends()).isEqualTo(1);
        assertThat(new BulkheadSettings(32).maxConcurrentSends()).isEqualTo(32);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new BulkheadSettings(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..32");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new BulkheadSettings(33))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..32");
    }
}
