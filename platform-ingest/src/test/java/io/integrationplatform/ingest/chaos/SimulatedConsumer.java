package io.integrationplatform.ingest.chaos;

import io.integrationplatform.ingest.port.OutboundRecord;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lane D's consumer-side duplicate check (T-009, E2-C2): the receiving side of the chaos scenarios. It accepts a
 * record the first time it sees its idempotency key and detects every later delivery of the same key as a
 * duplicate - the "or {@link OutboundRecord#idempotencyKey()} equality" path the brief allows while lane A's
 * full duplicate-detection task (T-010) lands elsewhere; the seam test
 * {@code OutboundRecordTest.idempotencyKeyIsStablePerOutboxRowAcrossRepublish} locks the key's identity.
 */
final class SimulatedConsumer {

    enum Outcome {
        FIRST_DELIVERY,
        DUPLICATE_DETECTED
    }

    private final Map<String, Integer> deliveriesPerKey = new ConcurrentHashMap<>();

    /** One arrival at the receiving side. */
    Outcome onReceive(OutboundRecord record) {
        int times = deliveriesPerKey.merge(record.idempotencyKey(), 1, Integer::sum);
        return times == 1 ? Outcome.FIRST_DELIVERY : Outcome.DUPLICATE_DETECTED;
    }

    /** Distinct keys that were delivered at all. */
    int distinctKeys() {
        return deliveriesPerKey.size();
    }

    /** Keys whose record arrived more than once - the duplicates, detected rather than silently delivered. */
    long duplicateKeys() {
        return deliveriesPerKey.values().stream().filter(times -> times > 1).count();
    }

    void reset() {
        deliveriesPerKey.clear();
    }
}
