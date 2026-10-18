package io.integrationplatform.delivery.replay;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E13 N2 (T111, T-166 narrow): the operator retry and replay of one delivery. T111 RED: compile-only skeleton.
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
    private final OutboxWriter outbox;
    private final ApplicationEventPublisher events;
    private final TenantContext tenants;
    private final TransactionOperations transactions;
    private final Clock clock;

    public DeliveryOperatorActions(OperatorActionStore store, OutboxWriter outbox, ApplicationEventPublisher events,
            TenantContext tenants, TransactionOperations transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.events = Objects.requireNonNull(events, "events");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Operator replay: {@code DEAD_LETTERED -> DUE} with the pinned versions. */
    public Result replay(UUID deliveryId, String actor) {
        throw new UnsupportedOperationException("T111 RED");
    }

    /** Operator retry: {@code RETRY_WAIT -> DUE} now, with the pinned versions. */
    public Result retry(UUID deliveryId, String actor) {
        throw new UnsupportedOperationException("T111 RED");
    }
}
