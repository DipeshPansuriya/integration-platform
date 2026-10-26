package io.integrationplatform.e18;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * T-153 (E18-F1-S3): the suite's memory budget and the check that fails a run over it. Pure: it judges a
 * {@link Measurement} against the {@link MemoryBudget} loaded from {@code e2e-full-budget.properties} (the test
 * configuration that records the budget), so "a run over budget fails and reports the peak" is provable without Docker
 * ({@code MemoryBudgetTest}).
 *
 * @param heapPeakMiB       the most heap (Runtime used = total - free) any sample may show during the bounded load
 * @param heapRetainedMiB   the most heap the JVM may hold after the load has drained and a GC (leak check), over the
 *                          pre-load baseline
 * @param poolPendingMax    the most threads that may wait for a database connection at one sample
 * @param backlogMax        the most outbox rows plus unsettled deliveries that may be outstanding at one sample
 * @param drainSeconds      how long the backlog may take to reach zero after the last message is posted
 */
record MemoryBudget(long heapPeakMiB, long heapRetainedMiB, int poolPendingMax, long backlogMax, int drainSeconds) {

    /** What one bounded-load window measured. */
    record Measurement(long heapPeakMiB, long heapRetainedMiB, int poolPendingPeak, int poolActivePeak, int poolMax,
            long backlogPeak, long drainSeconds) {
    }

    static MemoryBudget load() {
        Properties properties = new Properties();
        try (InputStream in = MemoryBudget.class.getResourceAsStream("/e2e-full-budget.properties")) {
            if (in == null) {
                throw new IllegalStateException("e2e-full-budget.properties is missing from the test resources");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("e2e-full-budget.properties could not be read", e);
        }
        return new MemoryBudget(Long.parseLong(properties.getProperty("heap.peak.mib")),
                Long.parseLong(properties.getProperty("heap.retained.growth.mib")),
                Integer.parseInt(properties.getProperty("pool.pending.max")),
                Long.parseLong(properties.getProperty("backlog.max")),
                Integer.parseInt(properties.getProperty("drain.seconds")));
    }

    /** Every breach, empty when the run is within budget. */
    List<String> breaches(Measurement m) {
        List<String> breaches = new ArrayList<>();
        if (m.heapPeakMiB() > heapPeakMiB) {
            breaches.add("peak heap " + m.heapPeakMiB() + " MiB exceeds the budget " + heapPeakMiB + " MiB");
        }
        if (m.heapRetainedMiB() > heapRetainedMiB) {
            breaches.add("heap retained after the load " + m.heapRetainedMiB() + " MiB over baseline exceeds the budget "
                    + heapRetainedMiB + " MiB");
        }
        if (m.poolPendingPeak() > poolPendingMax) {
            breaches.add("connection-pool waiters peaked at " + m.poolPendingPeak() + ", budget " + poolPendingMax);
        }
        if (m.poolMax() > 0 && m.poolActivePeak() > m.poolMax()) {
            breaches.add("pool active " + m.poolActivePeak() + " exceeds the pool maximum " + m.poolMax());
        }
        if (m.backlogPeak() > backlogMax) {
            breaches.add("queue backlog peaked at " + m.backlogPeak() + ", budget " + backlogMax);
        }
        if (m.drainSeconds() > drainSeconds) {
            breaches.add("the backlog took " + m.drainSeconds() + " s to drain, budget " + drainSeconds + " s");
        }
        return breaches;
    }

    /** Fails with the peak (and every other breach) named when the measurement is over budget. */
    void enforce(Measurement m) {
        List<String> breaches = breaches(m);
        if (!breaches.isEmpty()) {
            throw new AssertionError("OVER BUDGET - " + String.join("; ", breaches) + " [measured: " + m + "]");
        }
    }
}
