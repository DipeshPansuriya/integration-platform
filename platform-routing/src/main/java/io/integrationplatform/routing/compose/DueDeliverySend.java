package io.integrationplatform.routing.compose;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.adapter.api.TargetEndpointView;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import io.integrationplatform.routing.claim.DeliveryClaimHandler;
import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkKind;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.templates.RouteBuildRefusedException;
import io.integrationplatform.routing.templates.RouteConfigReader;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@code platform.delivery.due} work (T-049 composition; ADR-010 sections 2, 3 and 7): under the tenant the stage
 * base resolved from the delivery row - the claim transaction ({@code HELD}, a duplicate or an already-claimed row
 * end here with no effect); then the delivery's route instance, built lazily from the configuration rows on this
 * running instance (T-116, 0 deploys and 0 restarts) and dispatched with {@code direct:} only; the instance's send is
 * the layer-1 route, whose target call transforms through the delivery's pinned mapping version and calls the API
 * adapter. A send that ends with no handled failure moves {@code SENDING -> DELIVERED} under the claim token; a
 * handled failure was already recorded by layer 1's outcome hand-off (RETRY_WAIT or DEAD_LETTERED).
 *
 * <p>ponytail (R1.1, row 278 T71 / T-177): the send runs on this consumer's thread. The per-target bulkhead
 * dispatch ({@code stage2.bulkhead.DeliveryDueDispatch}, permit before claim, the send on the target's pool) replaces
 * this handler as the {@code platform.delivery.due} work when it lands; the dispatch it needs is {@link #send}.
 */
public final class DueDeliverySend implements StageConsumer.Handler {

    private static final Logger log = LoggerFactory.getLogger(DueDeliverySend.class);

    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final JdbcDueDeliveries dueDeliveries;
    private final DeliveryClaimHandler claims;
    private final RouteConfigReader configRows;
    private final RouteInstanceFactory routes;
    private final TargetEndpointViews targets;
    private final ProducerTemplate producer;
    private final SendOutcomes outcomes;
    private final Supplier<Instant> clock;

    public DueDeliverySend(WorkerTenantBinding binding, TransactionOperations transactions,
            JdbcDueDeliveries dueDeliveries, DeliveryClaimHandler claims, RouteConfigReader configRows,
            RouteInstanceFactory routes, TargetEndpointViews targets, ProducerTemplate producer,
            SendOutcomes outcomes, Supplier<Instant> clock) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dueDeliveries = Objects.requireNonNull(dueDeliveries, "dueDeliveries");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.configRows = Objects.requireNonNull(configRows, "configRows");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onWork(WorkRecord record, UUID tenantId) {
        if (WorkKind.of(record.type()) != WorkKind.DELIVERY) {
            throw new IllegalArgumentException("stage 2 handles delivery.due records only, got: " + record.type());
        }
        binding.runAs(tenantId, () -> claimAndSend(tenantId, record.id()));
    }

    private void claimAndSend(UUID tenantId, UUID deliveryId) {
        Optional<JdbcDueDeliveries.DueDelivery> found = transactions.execute(status -> dueDeliveries.due(deliveryId));
        if (found == null || found.isEmpty()) {
            return; // claimed, held or final already: acknowledged, no effect
        }
        JdbcDueDeliveries.DueDelivery due = found.get();
        UUID claimToken = UUID.randomUUID();
        if (claims.claim(due.orderingKey(), deliveryId, claimToken) != DeliveryClaimService.Outcome.CLAIMED) {
            return;
        }
        send(tenantId, due, claimToken);
    }

    /** The claimed delivery's send: its route instance, the layer-1 route behind it, the success move. */
    void send(UUID tenantId, JdbcDueDeliveries.DueDelivery due, UUID claimToken) {
        Prepared prepared = transactions.execute(status -> prepare(tenantId, due));
        Stage2Send send = new Stage2Send(due.id(), claimToken, due.orderingKey(), due.targetEndpointId(),
                prepared.idempotentReceive());
        if (prepared.row() == null) {
            dropWithoutRoute(send, "route " + due.routeId() + " is not an ACTIVE route of its interface any more");
            return;
        }
        RouteInstance instance;
        try {
            instance = routes.ensure(prepared.row());
        } catch (RouteBuildRefusedException refused) {
            refusedRoute(send, refused);
            return;
        }
        routes.touch(instance, clock.get());
        Exchange done = producer.send(instance.consumerUri(),
                exchange -> exchange.getIn().setBody(send));
        if (done.getException() != null) {
            // not a classified call failure (layer 1 records those itself): the hand-off or the ledger write failed.
            // The delivery stays SENDING under this claim for the reconciler (ADR-010 section 9); never re-sent here.
            log.error("stage 2: delivery {} - the send ended outside layer 1's classified outcomes; it stays SENDING"
                    + " for the reconciler", due.id(), done.getException());
            return;
        }
        if (done.getProperty(Exchange.EXCEPTION_CAUGHT) == null) {
            transactions.executeWithoutResult(status -> outcomes.markDelivered(due.id(), claimToken));
        }
    }

    private Prepared prepare(UUID tenantId, JdbcDueDeliveries.DueDelivery due) {
        boolean idempotent = targets.byEndpointId(due.targetEndpointId()).map(TargetEndpointView::idempotentReceive)
                .orElse(false);
        if (due.interfaceId() == null || due.routeId() == null) {
            return new Prepared(null, idempotent);
        }
        RouteConfigRow row = configRows.activeRoutes(tenantId, due.interfaceId()).stream()
                .filter(candidate -> candidate.routeId().equals(due.routeId())).findFirst().orElse(null);
        return new Prepared(row, idempotent);
    }

    /**
     * The T-120 seam (amit's T71 owns the allowedSchemes refusal itself): the route instance could not be built - an
     * unapproved endpoint URI scheme or no template for the target type - so nothing can be sent; the delivery is
     * dead-lettered PERMANENT under its claim, never retried against the same configuration.
     */
    private void refusedRoute(Stage2Send send, RouteBuildRefusedException refused) {
        log.warn("stage 2: delivery {} - its route instance was refused: {}", send.deliveryId(), refused.getMessage());
        transactions.executeWithoutResult(status -> outcomes.recordFailure(send.deliveryId(), send.claimToken(), 0,
                OutcomeClass.PERMANENT, send.targetDeclaresIdempotentReceive()));
    }

    private void dropWithoutRoute(Stage2Send send, String reason) {
        log.warn("stage 2: delivery {} - {}; dead-lettered PERMANENT", send.deliveryId(), reason);
        transactions.executeWithoutResult(status -> outcomes.recordFailure(send.deliveryId(), send.claimToken(), 0,
                OutcomeClass.PERMANENT, send.targetDeclaresIdempotentReceive()));
    }

    private record Prepared(RouteConfigRow row, boolean idempotentReceive) {
    }
}
