package io.integrationplatform.delivery.replay;

import io.integrationplatform.domain.audit.AuditActions;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E13 N2 (T111, T-166 narrow): the operator retry and replay of one delivery, sent again with the versions it
 * was pinned to (mapping and contract). One transaction per call: the row is locked, checked (state and both pins),
 * moved to {@code DUE} with its {@code delivery_event}, re-driven through a {@code DELIVERY_DUE} outbox record and
 * audited - the move, the re-drive and the audit commit or vanish together (transactional outbox, T-090 pattern).
 *
 * <p>The tenant is required BEFORE anything is read (R08/R11). A delivery the bound tenant cannot see is
 * {@code NOT_FOUND}; the wrong state or a missing pin changes nothing.
 */
public final class DeliveryOperatorActions {

    /** How an action ended. Only {@code MOVED_TO_DUE} changed anything. */
    public enum Outcome {
        MOVED_TO_DUE,
        NOT_FOUND,
        WRONG_STATE,
        NOT_PINNED
    }

    /** The answer: the outcome, the delivery's state after the call and the pinned versions it is sent with. */
    public record Result(Outcome outcome, UUID deliveryId, DeliveryState state, UUID mappingVersionId,
            UUID contractVersionId) {
    }

    private final OperatorActionStore store;
    private final ActiveTargetContracts activeContracts;
    private final OutboxWriter outbox;
    private final ApplicationEventPublisher events;
    private final TenantContext tenants;
    private final TransactionOperations transactions;
    private final Clock clock;

    public DeliveryOperatorActions(OperatorActionStore store, ActiveTargetContracts activeContracts,
            OutboxWriter outbox, ApplicationEventPublisher events,
            TenantContext tenants, TransactionOperations transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.activeContracts = Objects.requireNonNull(activeContracts, "activeContracts");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.events = Objects.requireNonNull(events, "events");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Operator replay: {@code DEAD_LETTERED -> DUE} with the pinned versions. */
    public Result replay(UUID deliveryId, String actor) {
        return act(deliveryId, actor, DeliveryState.DEAD_LETTERED, true);
    }

    /** Operator retry: {@code RETRY_WAIT -> DUE} now, with the pinned versions. */
    public Result retry(UUID deliveryId, String actor) {
        return act(deliveryId, actor, DeliveryState.RETRY_WAIT, false);
    }

    private Result act(UUID deliveryId, String actor, DeliveryState from, boolean replay) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(actor, "actor");
        UUID tenant = tenants.requireTenantId();
        return transactions.execute(status -> {
            var found = store.lockForAction(deliveryId);
            if (found.isEmpty()) {
                return new Result(Outcome.NOT_FOUND, deliveryId, null, null, null);
            }
            OperatorActionStore.Target target = found.get();
            if (target.state() != from) {
                return refused(Outcome.WRONG_STATE, target);
            }
            if (target.contractVersionId() == null) {
                // a mapped delivery without its contract pin is never guessed (the stage-1 guard and the CHECK make it
                // impossible); only a route WITHOUT a mapping resolves the target endpoint's active contract (T172)
                UUID resolved = target.mappingVersionId() != null || target.targetEndpointId() == null ? null
                        : activeContracts.activeContractVersionOf(target.targetEndpointId()).orElse(null);
                if (resolved == null || !store.pinContract(deliveryId, resolved)) {
                    return refused(Outcome.NOT_PINNED, target);
                }
                target = new OperatorActionStore.Target(deliveryId, target.state(), null, resolved,
                        target.targetEndpointId());
            }
            Instant at = clock.instant();
            boolean moved = replay ? store.replay(deliveryId, at) : store.retryNow(deliveryId, at);
            if (!moved) {
                return refused(Outcome.WRONG_STATE, target); // the row is locked: only a broken lock gets here
            }
            outbox.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, deliveryId, "delivery:" + deliveryId, null, null,
                    target.contractVersionId()));
            AuditEvent audit = replay
                    ? AuditActions.operatorReplay(actor, tenant, deliveryId, target.mappingVersionId(),
                            target.contractVersionId(), at)
                    : AuditActions.operatorRetry(actor, tenant, deliveryId, target.mappingVersionId(),
                            target.contractVersionId(), at);
            events.publishEvent(audit);
            return new Result(Outcome.MOVED_TO_DUE, deliveryId, DeliveryState.DUE, target.mappingVersionId(),
                    target.contractVersionId());
        });
    }

    private static Result refused(Outcome outcome, OperatorActionStore.Target target) {
        return new Result(outcome, target.deliveryId(), target.state(), target.mappingVersionId(),
                target.contractVersionId());
    }
}
