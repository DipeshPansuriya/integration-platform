package io.integrationplatform.delivery.internal.trigger;

import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.delivery.port.TargetBreakerFacts;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
    private final TargetBreakerFacts breakerFacts;
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
     * T-183 (ADR-010 section 6, last row; challenge S-i): with the per-target breaker facts - a target whose
     * breaker is not CLOSED gets at most {@code halfOpenTrialCalls} of its waiting deliveries re-driven per pass.
     */
    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize, InstanceReadiness readiness, DeliveryBulkheadFacts bulkheadFacts,
            TargetRedriveCap redriveCap, TargetBreakerFacts breakerFacts) {
        this(tenants, tenantContext, transactions, store, outboxWriter, batchSize, readiness, bulkheadFacts,
                redriveCap, DeliveryReconcileStore.none(), TargetSendFacts.unknown(), RetryPolicy.defaults(),
                breakerFacts);
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
        this(tenants, tenantContext, transactions, store, outboxWriter, batchSize, readiness, bulkheadFacts,
                redriveCap, reconcileStore, targetFacts, policy, TargetBreakerFacts.unknown());
    }

    public DeliveryRetryTrigger(ActiveTenants tenants, TriggerTenantContext tenantContext,
            TransactionOperations transactions, DeliveryRetryStore store, OutboxWriter outboxWriter,
            int batchSize, InstanceReadiness readiness, DeliveryBulkheadFacts bulkheadFacts,
            TargetRedriveCap redriveCap, DeliveryReconcileStore reconcileStore, TargetSendFacts targetFacts,
            RetryPolicy policy, TargetBreakerFacts breakerFacts) {
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
        this.breakerFacts = Objects.requireNonNull(breakerFacts, "breakerFacts");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.batchSize = batchSize;
    }

    /**
     * One pass: re-drive due deliveries of every active tenant.
     *
     * @return how many deliveries moved to {@code DUE} and were re-driven
     */
    public int runOnce() {
        if (!readiness.ready(null, null)) {
            return 0; // T-201: a not-ready instance skips the whole pass - no tenant bound, no transaction opened
        }
        int reDriven = 0;
        for (UUID tenant : tenants.activeTenantIds()) {
            tenantContext.bind(tenant);
            try {
                Integer moved = transactions.execute(status -> {
                    settleStaleClaims(tenant);
                    return releaseHeld() + redriveDue(tenant) + reHandUnhanded();
                });
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
        Map<UUID, Integer> breakerMovedPerTarget = new HashMap<>();
        for (RetryCandidate candidate : due) {
            if (!readiness.ready(tenant, candidate.deliveryId())) {
                continue; // readiness stub: never false today; E10 T-201 fills the rule
            }
            Optional<DeliveryBulkheadFacts.BulkheadFacts> facts = bulkheadFacts.forDelivery(candidate.deliveryId());
            Optional<UUID> deferredTarget = facts.filter(f -> f.bulkheadDeferrals() > 0)
                    .map(DeliveryBulkheadFacts.BulkheadFacts::targetEndpointId);
            if (deferredTarget.isPresent() && deferredMovedPerTarget.getOrDefault(deferredTarget.get(), 0)
                    >= redriveCap.maxDeferredRedrives(deferredTarget.get())) {
                continue; // T-199: over this target's cap - stays RETRY_WAIT for the next run
            }
            // T-183 (ADR-010 section 6): a target whose breaker is not CLOSED re-drives at most its trial count
            Optional<UUID> target = facts.map(DeliveryBulkheadFacts.BulkheadFacts::targetEndpointId);
            Optional<TargetBreakerFacts.BreakerFacts> breaker = target.flatMap(breakerFacts::forTarget);
            boolean trialCapped = breaker.isPresent()
                    && breaker.get().state() != TargetBreakerFacts.BreakerFacts.State.CLOSED;
            if (trialCapped && breakerMovedPerTarget.getOrDefault(target.get(), 0)
                    >= breaker.get().halfOpenTrialCalls()) {
                continue;
            }
            if (!store.markDue(candidate.deliveryId())) {
                continue; // 0 rows: the delivery is not ours to move anymore - the store never throws on this
            }
            outboxWriter.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, candidate.deliveryId(),
                    "delivery:" + candidate.deliveryId(), null, null));
            deferredTarget.ifPresent(id -> deferredMovedPerTarget.merge(id, 1, Integer::sum));
            if (trialCapped) {
                breakerMovedPerTarget.merge(target.get(), 1, Integer::sum);
            }
            moved++;
        }
        return moved;
    }

    /** T-180: a PENDING/DUE row unchanged for {@link #UNHANDED_AFTER} with no unpublished outbox row is re-handed. */
    private int reHandUnhanded() {
        int reHanded = 0;
        for (UUID id : reconcileStore.lockUnhanded(Instant.now().minus(UNHANDED_AFTER), batchSize)) {
            outboxWriter.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, id, "delivery:" + id, null, null));
            reHanded++;
        }
        return reHanded;
    }

    private static final Duration UNHANDED_AFTER = Duration.ofMinutes(5);

    /**
     * T-180 (ADR-011 D6): a HELD delivery whose predecessors are all final but whose release was missed is
     * released here, writing its due record in the same transaction.
     */
    private int releaseHeld() {
        int released = 0;
        for (UUID held : reconcileStore.lockReleasableHeld(batchSize)) {
            if (!reconcileStore.release(held)) {
                continue; // 0 rows: no longer HELD - nothing to re-drive
            }
            outboxWriter.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, held, "delivery:" + held, null, null));
            released++;
        }
        return released;
    }

    /**
     * T-180 (ADR-010 section 6): a SENDING claim past {@code staleClaimLimit(target timeout)} is settled as an
     * AMBIGUOUS outcome - never re-sent straight away: a non-idempotent target dead-letters, an idempotent one
     * schedules a retry (or dead-letters once out of attempts).
     */
    private void settleStaleClaims(UUID tenant) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(policy.staleClaimLimit(Duration.ZERO)); // the shortest limit any target has
        for (DeliveryReconcileStore.SendingClaim claim : reconcileStore.sendingClaimedBefore(cutoff, batchSize)) {
            // ponytail: unknown target facts assume the longest timeout (300 s, the config max) and no idempotency
            TargetSendFacts.Facts facts = targetFacts.forTarget(claim.targetId())
                    .orElse(new TargetSendFacts.Facts(UNKNOWN_TARGET_TIMEOUT, false));
            if (claim.claimedAt().plus(policy.staleClaimLimit(facts.timeout())).isAfter(now)) {
                continue; // still inside this target's limit: the send may yet report
            }
            boolean settled = facts.idempotentReceive() && claim.attempts() < policy.maxAttempts()
                    ? store.scheduleRetry(claim.deliveryId(), claim.claimToken(), policy.nextRetryAt(now,
                            claim.attempts() + 1, null, ThreadLocalRandom.current().nextDouble()))
                    : store.deadLetter(claim.deliveryId(), claim.claimToken(), OutcomeClass.AMBIGUOUS);
            if (settled) {
                LOG.log(System.Logger.Level.INFO, "stale claim settled as AMBIGUOUS: tenant {0}, delivery {1}",
                        tenant, claim.deliveryId());
            }
        }
    }

    private static final Duration UNKNOWN_TARGET_TIMEOUT = Duration.ofSeconds(300);
    private static final System.Logger LOG = System.getLogger(DeliveryRetryTrigger.class.getName());
}
