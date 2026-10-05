package io.integrationplatform.delivery.internal.trigger;

import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The delivery-retry trigger (T-090; ADR-010 section 4 "a polling trigger, phase 2"): the minimal
 * DB-polling re-drive - no scheduler, no queue. Every run loops the active tenants (ADR-011 Decision 8) and per
 * tenant executes ONE transaction that locks due {@code RETRY_WAIT} deliveries through lane C's
 * {@link DeliveryRetryStore} contract ({@code FOR UPDATE SKIP LOCKED}, oldest retry time first), moves each to
 * {@code DUE} and re-drives it by appending a {@code DELIVERY_DUE} outbox row - same transaction, so the move
 * and the re-drive publish commit or vanish together; a crash leaves the delivery due for the next run. The
 * record key {@code delivery:<id>} is stable for the delivery, so every re-drive of it lands in the same key
 * bucket and keeps its order (ADR-005 Decision 11).
 *
 * <p>It only imports ports: the store and the writer open no transaction of their own (their contracts), and
 * this class never touches a broker client. When nothing is due it does nothing; the batch limit bounds one
 * transaction's work.
 */
public final class DeliveryRetryTrigger {

    private final ActiveTenants tenants;
    private final TriggerTenantContext tenantContext;
    private final TransactionOperations transactions;
    private final DeliveryRetryStore store;
    private final OutboxWriter outboxWriter;
    private final int batchSize;

    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.store = Objects.requireNonNull(store, "store");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter");
        this.batchSize = batchSize;
    }

    /**
     * One pass: re-drive due deliveries of every active tenant.
     *
     * @return how many deliveries moved to {@code DUE} and were re-driven
     */
    public int runOnce() {
        int reDriven = 0;
        for (UUID tenant : tenants.activeTenantIds()) {
            tenantContext.bind(tenant);
            try {
                Integer moved = transactions.execute(status -> redriveDue());
                reDriven += moved == null ? 0 : moved;
            } finally {
                tenantContext.unbind();
            }
        }
        return reDriven;
    }

    private int redriveDue() {
        List<RetryCandidate> due = store.lockDue(Instant.now(), batchSize);
        int moved = 0;
        for (RetryCandidate candidate : due) {
            if (!store.markDue(candidate.deliveryId())) {
                continue; // 0 rows: the delivery is not ours to move anymore - the store never throws on this
            }
            outboxWriter.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, candidate.deliveryId(),
                    "delivery:" + candidate.deliveryId(), null, null));
            moved++;
        }
        return moved;
    }
}
