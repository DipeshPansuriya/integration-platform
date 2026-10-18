package io.integrationplatform.delivery.internal.trigger;

import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final InstanceReadiness readiness;
    private final DeliveryBulkheadFacts bulkheadFacts;
    private final TargetRedriveCap redriveCap;
    private final DeliveryReconcileStore reconcileStore;
    private final TargetSendFacts targetFacts;
    private final RetryPolicy policy;
    private final int batchSize;

    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize) {
        this(tenants, tenantContext, transactions, store, outboxWriter, batchSize, InstanceReadiness.alwaysReady());
    }

    /**
     * E9 seam (arjun-fullstack, F14): the readiness gate stub - consulted before each re-drive
     * (E10's T-201 readiness rules slot in here; the always-ready default changes nothing today).
     */
    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize, InstanceReadiness readiness) {
        this(tenants, tenantContext, transactions, store, outboxWriter, batchSize, readiness,
                deliveryId -> Optional.empty(), TargetRedriveCap.unlimited());
    }

    /**
     * T-199 (ADR-010 section 2): with the per-target re-drive cap - at most {@code redriveCap} bulkhead-deferred
     * deliveries of one target move to {@code DUE} per run; the rest stay in {@code RETRY_WAIT} for the next run.
     * Deliveries that were never bulkhead-deferred are not counted against the cap.
     */
    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize, InstanceReadiness readiness, DeliveryBulkheadFacts bulkheadFacts,
            TargetRedriveCap redriveCap) {
        this(tenants, tenantContext, transactions, store, outboxWriter, batchSize, readiness, bulkheadFacts,
                redriveCap, DeliveryReconcileStore.none(), TargetSendFacts.unknown(), RetryPolicy.defaults());
    }

    /**
     * T-180 (E9-F3-S1): with the delivery side of the reconciler sweep - in each tenant's transaction, before the
     * re-drive, stale {@code SENDING} claims are settled and held deliveries whose predecessors are final are
     * released.
     */
    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize, InstanceReadiness readiness, DeliveryBulkheadFacts bulkheadFacts,
            TargetRedriveCap redriveCap, DeliveryReconcileStore reconcileStore, TargetSendFacts targetFacts,
            RetryPolicy policy) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.store = Objects.requireNonNull(store, "store");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.bulkheadFacts = Objects.requireNonNull(bulkheadFacts, "bulkheadFacts");
        this.redriveCap = Objects.requireNonNull(redriveCap, "redriveCap");
        this.reconcileStore = Objects.requireNonNull(reconcileStore, "reconcileStore");
        this.targetFacts = Objects.requireNonNull(targetFacts, "targetFacts");
        this.policy = Objects.requireNonNull(policy, "policy");
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
                Integer moved = transactions.execute(status -> redriveDue(tenant));
                reDriven += moved == null ? 0 : moved;
            } finally {
                tenantContext.unbind();
            }
        }
        return reDriven;
    }

    private int redriveDue(UUID tenant) {
        List<RetryCandidate> due = store.lockDue(Instant.now(), batchSize);
        int moved = 0;
        Map<UUID, Integer> deferredMovedPerTarget = new HashMap<>();
        for (RetryCandidate candidate : due) {
            if (!readiness.ready(tenant, candidate.deliveryId())) {
                continue; // readiness stub: never false today; E10 T-201 fills the rule
            }
            Optional<UUID> deferredTarget = bulkheadFacts.forDelivery(candidate.deliveryId())
                    .filter(facts -> facts.bulkheadDeferrals() > 0)
                    .map(DeliveryBulkheadFacts.BulkheadFacts::targetEndpointId);
            if (deferredTarget.isPresent() && deferredMovedPerTarget.getOrDefault(deferredTarget.get(), 0)
                    >= redriveCap.maxDeferredRedrives(deferredTarget.get())) {
                continue; // T-199: over this target's cap - stays RETRY_WAIT for the next run
            }
            if (!store.markDue(candidate.deliveryId())) {
                continue; // 0 rows: the delivery is not ours to move anymore - the store never throws on this
            }
            outboxWriter.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, candidate.deliveryId(),
                    "delivery:" + candidate.deliveryId(), null, null));
            deferredTarget.ifPresent(target -> deferredMovedPerTarget.merge(target, 1, Integer::sum));
            moved++;
        }
        return moved;
    }
}
