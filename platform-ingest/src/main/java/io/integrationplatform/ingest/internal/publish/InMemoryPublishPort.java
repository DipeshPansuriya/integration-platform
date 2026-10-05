package io.integrationplatform.ingest.internal.publish;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The in-memory {@link PublishPort} adapter (T-042): every E2 test drives the relay through the real port type before
 * Kafka exists, and E8 replaces this adapter with the Kafka one in {@code platform-routing} without touching the relay
 * (E2-F1-S2). {@link #publish} records the record and returns at once; {@link #acknowledge} answers {@code true}
 * unless the row was refused with {@link #refuseAcknowledgeOf(long)} - the timeout parameter matters only to
 * broker-backed adapters.
 */
public final class InMemoryPublishPort implements PublishPort {

    private final List<OutboundRecord> published = new CopyOnWriteArrayList<>();
    private final Set<Long> refusedAcknowledgements = ConcurrentHashMap.newKeySet();

    @Override
    public PublishReceipt publish(OutboundRecord record) {
        published.add(record);
        return new InMemoryReceipt(record.outboxId());
    }

    @Override
    public boolean acknowledge(PublishReceipt receipt, Duration timeout) {
        return !refusedAcknowledgements.contains(receipt.outboxId());
    }

    /** Every record published so far, in publish order. */
    public List<OutboundRecord> published() {
        return List.copyOf(published);
    }

    /** Makes the acknowledge of one row fail - the relay must then leave it unpublished for a later cycle. */
    public void refuseAcknowledgeOf(long outboxId) {
        refusedAcknowledgements.add(outboxId);
    }

    /** Empties the log and the refusals (the next test or the next cycle starts clean). */
    public void reset() {
        published.clear();
        refusedAcknowledgements.clear();
    }

    private record InMemoryReceipt(long outboxId) implements PublishReceipt {
    }
}
