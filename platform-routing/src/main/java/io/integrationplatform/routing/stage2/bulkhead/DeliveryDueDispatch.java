package io.integrationplatform.routing.stage2.bulkhead;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.routing.adapter.api.TargetEndpointView;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import io.integrationplatform.routing.stage.WorkKind;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
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
 *   <li>with the permit held: the claim transaction (HELD or a duplicate returns the permit at once,
 *       acknowledged);</li>
 *   <li>{@code CLAIMED}: hand the send to the target's pool thread - the target call, the layer-1
 *       redelivery and the outcome path all live there; the permit returns when the send finishes.</li>
 * </ol>
 *
 * <p>No target call is reachable from this thread except by that hand-off (E9-F1-S4 "the partition thread
 * never makes a target call"): the only thing this class does after {@link TargetBulkhead#execute} is
 * return. Best-effort by construction (E9-C9): each target's pool is its own, one full or hanging target
 * defers or slows only its own work. The tenant binding of the stage base (E8) is the caller's: like the
 * claim handler, this class runs under the record's tenant and is Kafka- and database-client-free (R12).
 */
public final class DeliveryDueDispatch implements io.integrationplatform.routing.stage.StageConsumer.Handler {

    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final DeliveryBulkheadFacts facts;
    private final BulkheadRegistry bulkheads;
    private final Stage2Claim claims;
    private final BulkheadDeferrals deferrals;
    private final Stage2SendDispatch sends;
    private final TargetEndpointViews targets;
    private final Supplier<Instant> clock;
    private final DoubleSupplier jitter;

    public DeliveryDueDispatch(WorkerTenantBinding binding, TransactionOperations transactions,
            DeliveryBulkheadFacts facts, BulkheadRegistry bulkheads, Stage2Claim claims,
            BulkheadDeferrals deferrals, Stage2SendDispatch sends, TargetEndpointViews targets,
            Supplier<Instant> clock, DoubleSupplier jitter) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.bulkheads = Objects.requireNonNull(bulkheads, "bulkheads");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.deferrals = Objects.requireNonNull(deferrals, "deferrals");
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
        UUID deliveryId = record.id();
        Optional<BulkheadFacts> read = facts.forDelivery(deliveryId);
        if (read.isEmpty()) {
            return; // no visible row: acknowledged with no effect
        }
        BulkheadFacts delivery = read.get();
        TargetBulkhead bulkhead = bulkheads.of(delivery.targetEndpointId());
        if (!bulkhead.tryAcquire()) {
            deferrals.defer(deliveryId, clock.get(), delivery.bulkheadDeferrals(), jitter.getAsDouble());
            return; // deferred or already moved (0 rows = no effect): acknowledged, the thread moves on
        }
        boolean handedOff = false;
        try {
            UUID claimToken = UUID.randomUUID();
            DeliveryClaimService.Outcome outcome = claims.claim(delivery.orderingKey(), deliveryId, claimToken);
            if (outcome != DeliveryClaimService.Outcome.CLAIMED) {
                return; // HELD or duplicate: acknowledged; the permit returns in the finally
            }
            Stage2Send send = new Stage2Send(deliveryId, claimToken, delivery.orderingKey(),
                    delivery.targetEndpointId(), idempotentReceive(delivery.targetEndpointId()));
            Runnable task = () -> {
                try {
                    sends.dispatch(send);
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

    /** The pinned target's idempotent-receive declaration (the send carries it for the classifier, ADR-010 section 5). */
    private boolean idempotentReceive(UUID targetEndpointId) {
        return targets.byEndpointId(targetEndpointId)
                .map(TargetEndpointView::idempotentReceive)
                .orElse(false); // endpoint row gone: the send fails on its own; no declaration to pin
    }
}
