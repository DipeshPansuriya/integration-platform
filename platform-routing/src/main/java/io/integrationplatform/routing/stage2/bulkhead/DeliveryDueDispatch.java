package io.integrationplatform.routing.stage2.bulkhead;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.BulkheadRedrive;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.routing.adapter.api.TargetEndpointView;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import io.integrationplatform.routing.stage.WorkKind;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.ingest.port.CorrelationContext;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@code platform.delivery.due} handler on the partition thread (T-177, E9-F1-S4, ADR-010 section 2
 * "deliver"): per record, in this order and no other -
 * <ol>
 *   <li>read the delivery's bulkhead facts (no lock, tenant-scoped);</li>
 *   <li>take the target's bulkhead permit, NON-BLOCKING: none available -> {@link
 *       BulkheadDeferrals#defer} the still-{@code PENDING}/{@code DUE} delivery through the conditional,
 *       zero-attempt-row deferral update and acknowledge the record (0 rows = another transaction already
 *       moved it - also just acknowledged). The partition thread never waits and never claims on a full
 *       pool;</li>
 *   <li>with the permit held: the pinned target's idempotent-receive flag, read in its own tenant-scoped
 *       transaction BEFORE the claim (B19 F1 - a read after the claim committed could fail and strand the
 *       CLAIMED/SENDING row; a failure here claims nothing and the stage base retries the record);</li>
 *   <li>the claim transaction (HELD or a duplicate returns the permit at once, acknowledged);</li>
 *   <li>{@code CLAIMED}: hand the send to the target's pool thread - the target call, the layer-1
 *       redelivery and the outcome path all live there, under the record's tenant bound on that thread and
 *       unbound when the send ends (B19 F3); the permit returns when the send finishes.</li>
 * </ol>
 *
 * <p>No target call is reachable from this thread except by that hand-off (E9-F1-S4 "the partition thread
 * never makes a target call"): the only thing this class does after {@link TargetBulkhead#execute} is
 * return. Best-effort by construction (E9-C9): each target's pool is its own, one full or hanging target
 * defers or slows only its own work. The record's tenant (resolved by the stage base through the protected path,
 * never from the record) is bound here with {@link WorkerTenantBinding} for the whole handler, as the T106 stage
 * handlers do; this class stays Kafka- and database-client-free (R12).
 */
public final class DeliveryDueDispatch implements io.integrationplatform.routing.stage.StageConsumer.Handler {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryDueDispatch.class);
    /** F3: HELD/duplicate claims in a row (per target) that may each hand the turn to the next deferred candidate. */
    static final int HELD_CHAIN_CAP = 8;

    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final DeliveryBulkheadFacts facts;
    private final BulkheadRegistry bulkheads;
    private final Stage2Claim claims;
    private final BulkheadDeferrals deferrals;
    private final BulkheadRedrive redrive;
    /** F1: targets this instance deferred a delivery for since their last empty re-drive scan (cross-instance: the job). */
    private final java.util.Set<UUID> deferredHere = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** F3: consecutive HELD/duplicate claims per target since the last real send (sticky at the cap). */
    private final java.util.concurrent.ConcurrentHashMap<UUID, Integer> heldInARow =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Stage2SendDispatch sends;
    private final TargetEndpointViews targets;
    private final Supplier<Instant> clock;
    private final DoubleSupplier jitter;

    public DeliveryDueDispatch(WorkerTenantBinding binding, TransactionOperations transactions,
            DeliveryBulkheadFacts facts, BulkheadRegistry bulkheads, Stage2Claim claims,
            BulkheadDeferrals deferrals, BulkheadRedrive redrive, Stage2SendDispatch sends,
            TargetEndpointViews targets,
            Supplier<Instant> clock, DoubleSupplier jitter) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.bulkheads = Objects.requireNonNull(bulkheads, "bulkheads");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.deferrals = Objects.requireNonNull(deferrals, "deferrals");
        this.redrive = Objects.requireNonNull(redrive, "redrive");
        this.sends = Objects.requireNonNull(sends, "sends");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    @Override
    public void onWork(WorkRecord record, UUID tenantId) {
        WorkKind kind = WorkKind.of(record.type());
        if (kind != WorkKind.DELIVERY) {
            throw new IllegalArgumentException("the delivery.due dispatch handles DELIVERY work records only,"
                    + " got: " + record.type());
        }
        binding.runAs(tenantId, () -> dispatch(tenantId, record.id(), record.traceparent()));
    }

    private void dispatch(UUID tenantId, UUID deliveryId, String traceparent) {
        Optional<BulkheadFacts> read = facts.forDelivery(deliveryId);
        if (read.isEmpty()) {
            return; // no visible row: acknowledged with no effect
        }
        BulkheadFacts delivery = read.get();
        TargetBulkhead bulkhead = bulkheads.of(delivery.targetEndpointId());
        if (!bulkhead.tryAcquire()) {
            if (deferrals.defer(deliveryId, clock.get(), delivery.bulkheadDeferrals(), jitter.getAsDouble())) {
                deferredHere.add(delivery.targetEndpointId()); // F1: a send end of this target now has something to scan for
            }
            return; // deferred or already moved (0 rows = no effect): acknowledged, the thread moves on
        }
        boolean handedOff = false;
        try {
            boolean idempotent = Boolean.TRUE.equals(
                    transactions.execute(status -> idempotentReceive(delivery.targetEndpointId())));
            UUID claimToken = UUID.randomUUID();
            DeliveryClaimService.Outcome outcome = claims.claim(delivery.orderingKey(), deliveryId, claimToken);
            if (outcome != DeliveryClaimService.Outcome.CLAIMED) {
                chainAfterHeld(delivery.targetEndpointId());
                return; // HELD or duplicate: acknowledged; the permit returns in the finally
            }
            heldInARow.remove(delivery.targetEndpointId()); // a real send: the next HELD run starts at zero
            Stage2Send send = new Stage2Send(deliveryId, claimToken, delivery.orderingKey(),
                    delivery.targetEndpointId(), idempotent);
            Runnable task = () -> {
                try (CorrelationContext.Scope journey = CorrelationContext.open(traceparent)) {
                    CorrelationContext.tag(CorrelationContext.DELIVERY_ID, deliveryId);
                    CorrelationContext.tag(CorrelationContext.TENANT_ID, tenantId);
                    // B19 F3: the binding is thread-confined, so the pool thread binds the record's tenant itself;
                    // runAs unbinds in its own finally, before the permit returns - nothing leaks to the next task
                    binding.runAs(tenantId, () -> sendThenRedrive(send, delivery.targetEndpointId()));
                } finally {
                    bulkhead.release();
                }
            };
            handedOff = bulkhead.execute(task);
            // handedOff=false (pool shut down) keeps the SENDING row for the reconciler (ADR-010 section 9);
            // the permit returns in the finally like any other early exit.
        } finally {
            if (!handedOff) {
                bulkhead.release();
            }
        }
    }

    /**
     * Drain fix (ADR-010 section 2 addendum): the send ends, its slot is about to free - so ONE bulkhead-deferred
     * delivery of the same target is re-driven now, in the same tenant binding, instead of waiting for the 60 s
     * retry job. One per finished send keeps the per-target cap, and a re-driven record only runs again by taking a
     * permit through {@link #dispatch}, so there is no loop. A failing re-drive is logged and swallowed: it must
     * never fail the finished send; the retry job remains the safety net.
     */
    private void sendThenRedrive(Stage2Send send, UUID targetEndpointId) {
        boolean circuitDeferred = false;
        try {
            circuitDeferred = sends.dispatch(send);
        } finally {
            if (!circuitDeferred) { // F2: an open breaker is not a freed slot of a healthy target - the job stays the net
                safeRedrive(targetEndpointId, "after a finished send", BulkheadRedrive.Cause.SLOT_FREE);
            }
        }
    }

    /**
     * F3: a HELD or duplicate claim produced no send, so nothing would re-drive the next deferred delivery and the
     * tail would fall to the 60 s job. Hand the turn on, at most {@link #HELD_CHAIN_CAP} in a row per target;
     * only a claim that would actually re-drive (a local deferral waits) counts toward the cap.
     */
    private void chainAfterHeld(UUID targetEndpointId) {
        if (!deferredHere.contains(targetEndpointId)) {
            return; // T190-1: nothing deferred here means no re-drive would run; a no-op must not spend the cap
        }
        // sticky (ADR-010): once the cap is reached the chain stays off until a real send resets the count; the
        // counter is clamped so it cannot grow without bound while the target stays HELD
        int run = heldInARow.merge(targetEndpointId, 1, (count, one) -> Math.min(count + one, HELD_CHAIN_CAP + 1));
        if (run > HELD_CHAIN_CAP) {
            return; // cap reached: the retry job takes the rest
        }
        safeRedrive(targetEndpointId, "after a HELD/duplicate claim", BulkheadRedrive.Cause.HELD_CHAIN);
    }

    /** A failing re-drive never fails the caller: WARN without a stack trace, the stack at DEBUG. */
    private void safeRedrive(UUID targetEndpointId, String when, BulkheadRedrive.Cause cause) {
        try {
            redriveIfDeferredHere(targetEndpointId, cause);
        } catch (RuntimeException failed) {
            LOG.warn("slot-free re-drive {} for target {} failed ({}); the delivery retry job will pick up the"
                    + " deferred delivery", when, targetEndpointId, failed.toString());
            LOG.debug("slot-free re-drive failure detail", failed);
        }
    }

    /**
     * F1: scans (one transaction, under the held permit) only when this instance deferred a delivery of the target
     * since the last scan that found nothing. The flag is cleared BEFORE the scan, so a deferral landing during it is
     * kept; a scan that moved a row (more may wait) or failed puts it back. A deferral made by another instance is the
     * retry job's to find (it stays the safety net).
     */
    private void redriveIfDeferredHere(UUID targetEndpointId, BulkheadRedrive.Cause cause) {
        if (!deferredHere.remove(targetEndpointId)) {
            return;
        }
        if (cause == BulkheadRedrive.Cause.SLOT_FREE) {
            heldInARow.remove(targetEndpointId); // T190: a freed slot re-drives a deferral - what was counted before it no longer binds
        }
        boolean moreMayWait = true; // a failing scan keeps the flag
        try {
            moreMayWait = redrive.redriveOneDeferred(targetEndpointId, cause);
        } finally {
            if (moreMayWait) {
                deferredHere.add(targetEndpointId);
            }
        }
    }

    /** The pinned target's idempotent-receive declaration (the send carries it for the classifier, ADR-010 section 5). */
    private boolean idempotentReceive(UUID targetEndpointId) {
        return targets.byEndpointId(targetEndpointId)
                .map(TargetEndpointView::idempotentReceive)
                .orElse(false); // endpoint row gone: the send fails on its own; no declaration to pin
    }
}
