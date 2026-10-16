package io.integrationplatform.routing.stage2.bulkhead;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One target's bounded pool (T-177, E9-F1-S4, ADR-010 section 2): at most {@code max_concurrent_sends} sends
 * run at a time for this target endpoint, on threads of this pool alone - the Kafka partition thread never
 * makes a target call. The contract is the ADR's: <em>bounded, non-blocking acquire, no hidden in-memory
 * queue</em>. A permit is a counting semaphore taken with {@link #tryAcquire()} (never {@code acquire()} -
 * waiting would occupy the partition thread); work runs on a fixed thread pool with a {@link SynchronousQueue}
 * (length 0: with the semaphore bounding the in-flight count to the pool size, an accepted task always finds a
 * thread). One slow or hanging target therefore occupies only its own pool (E9-C9).
 *
 * <p>The pool is per instance; the effective limit for a target is this size times the instance count.
 */
public final class TargetBulkhead {

    private final Semaphore permits;
    private final ThreadPoolExecutor pool;

    TargetBulkhead(int maxConcurrentSends, UUID targetEndpointId) {
        this(new BulkheadSettings(maxConcurrentSends), targetEndpointId);
    }

    TargetBulkhead(BulkheadSettings settings, UUID targetEndpointId) {
        int bound = settings.maxConcurrentSends();
        String nameTag = Long.toString(Math.abs(targetEndpointId.getMostSignificantBits()
                ^ targetEndpointId.getLeastSignificantBits()), 36);
        this.permits = new Semaphore(bound);
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(bound, bound, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "bulkhead-" + nameTag + "-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.pool.allowCoreThreadTimeOut(true);
    }

    /** Non-blocking permit take - {@code false} means the bulkhead is full: defer, never wait. */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /** Returns a permit (a claim that ended HELD/duplicate, or a finished send). */
    public void release() {
        permits.release();
    }

    /**
     * Hands the task to this target's pool. Called only while holding a permit, so the in-flight count is at
     * most the pool size and the hand-off always finds a thread; the {@code false} return is the defensive
     * path (pool shut down mid-shutdown) - the caller returns its permit and the database's own recovery
     * (the reconciler of ADR-010 section 9) re-drives the work.
     *
     * @apiNote the task MUST return the permit ({@link #release()}) in a {@code finally} on every path;
     *     {@link DeliveryDueDispatch} wraps the send so it always does.
     */
    public boolean execute(Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RuntimeException rejected) { // RejectedExecutionException only - the defensive path
            return false;
        }
    }

    /** Stops accepting new work (instance shutdown); in-flight sends finish on their threads. */
    public void shutdown() {
        pool.shutdown();
    }
}
