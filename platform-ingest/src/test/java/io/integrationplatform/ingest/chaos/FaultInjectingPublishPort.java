package io.integrationplatform.ingest.chaos;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lane D's fault-injecting decorator of the frozen {@link PublishPort} (T-009). It is the ONLY port the relay
 * sees in this suite (registered {@code @Primary} by {@link OutboxChaosTestApplication}) - a fake broker log plus
 * one-shot crash arming, never a stub of a lane's real class.
 *
 * <p>A crash is modelled by a {@link SimulatedCrash} that <em>extends {@link Error}</em>: the chaos points are
 * "the process dies", and a relay that catches {@code Exception} per row to be resilient must not be able to
 * swallow the kill - only a {@code Throwable} catch (a contract violation: the row would be lost) would. The arm
 * disarms when it fires, so the restarted cycle after the crash runs clean.
 *
 * <p>Chaos points (the two scenarios of E2-F1-S1):
 * <ul>
 *   <li>{@link CrashPoint#BEFORE_PUBLISH} - killed after the commit and before the publish: nothing reaches the
 *       broker log, nothing can be marked.</li>
 *   <li>{@link CrashPoint#AFTER_PUBLISH_BEFORE_ACK} - killed after the record reached the broker and before
 *       {@link #acknowledge} ever ran: the row stays unmarked, the restarted relay re-publishes it, and the
 *       consumer-side idempotency check must catch the duplicate (E2-C2).</li>
 * </ul>
 */
final class FaultInjectingPublishPort implements PublishPort {

    /** Where the simulated process death fires on the next {@link #publish} call. */
    enum CrashPoint {
        BEFORE_PUBLISH,
        AFTER_PUBLISH_BEFORE_ACK
    }

    /** The simulated crash - an {@link Error} so a per-row {@code Exception} handler cannot hide it (see javadoc). */
    static final class SimulatedCrash extends Error {

        SimulatedCrash(String message) {
            super(message);
        }
    }

    /** The receipt the seam contract returns for one record: the outbox row it was read from. */
    private record ChaosReceipt(long outboxId) implements PublishReceipt {
    }

    private final List<OutboundRecord> broker = new CopyOnWriteArrayList<>();
    private final AtomicReference<CrashPoint> armed = new AtomicReference<>();

    void armCrash(CrashPoint point) {
        armed.set(java.util.Objects.requireNonNull(point, "point"));
    }

    /** The kill: record where we died, disarm the one-shot, throw. */
    private void crash(CrashPoint point, OutboundRecord record) {
        armed.set(null);
        throw new SimulatedCrash("simulated relay crash " + point + " for outbox row " + record.outboxId());
    }

    @Override
    public PublishReceipt publish(OutboundRecord record) {
        if (armed.get() == CrashPoint.BEFORE_PUBLISH) {
            crash(CrashPoint.BEFORE_PUBLISH, record);
        }
        broker.add(record);
        if (armed.get() == CrashPoint.AFTER_PUBLISH_BEFORE_ACK) {
            crash(CrashPoint.AFTER_PUBLISH_BEFORE_ACK, record);
        }
        return new ChaosReceipt(record.outboxId());
    }

    @Override
    public boolean acknowledge(PublishReceipt receipt, Duration timeout) {
        // The in-memory adapter's contract: a published record is acknowledged. (A false ack would leave the row
        // unpublished - the acknowledge-failure chaos point belongs to lane B's own T-008 tests, not this suite.)
        return true;
    }

    /** Everything this port ever "sent" - survives a simulated crash exactly like a broker receipt would. */
    List<OutboundRecord> brokerLog() {
        return List.copyOf(broker);
    }

    /** Disarm without touching the broker log: the state of a fresh process after the crash. */
    void clearFault() {
        armed.set(null);
    }

    /** Full reset between tests: an empty broker, no armed fault. */
    void reset() {
        broker.clear();
        armed.set(null);
    }
}
