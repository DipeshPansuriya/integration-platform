package io.integrationplatform.routing.compose;

import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.bulkhead.Stage2SendDispatch;
import io.integrationplatform.routing.templates.RouteBuildRefusedException;
import io.integrationplatform.routing.templates.RouteConfigReader;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.TenantContext;
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
 * Stage 2's send of one CLAIMED delivery (T-049/T-177). {@code DeliveryDueDispatch} reads the facts, takes a
 * bulkhead permit, claims, and calls this inside the permit under the worker's tenant binding. Layer 1 (T-120)
 * records every classified outcome; a send ending outside them stays SENDING for the reconciler. A route no longer
 * ACTIVE, or whose instance is refused, is dead-lettered PERMANENT.
 */
public final class RouteInstanceSendDispatch implements Stage2SendDispatch {

    private static final Logger log = LoggerFactory.getLogger(RouteInstanceSendDispatch.class);

    private final TransactionOperations transactions;
    private final JdbcDueDeliveries deliveries;
    private final TenantContext tenantContext;
    private final RouteConfigReader configRows;
    private final RouteInstanceFactory routes;
    private final ProducerTemplate producer;
    private final SendOutcomes outcomes;
    private final Supplier<Instant> clock;

    public RouteInstanceSendDispatch(TransactionOperations transactions, JdbcDueDeliveries deliveries,
            TenantContext tenantContext, RouteConfigReader configRows, RouteInstanceFactory routes,
            ProducerTemplate producer, SendOutcomes outcomes, Supplier<Instant> clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.configRows = Objects.requireNonNull(configRows, "configRows");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void dispatch(Stage2Send send) {
        UUID tenantId = tenantContext.requireTenantId();
        Optional<JdbcDueDeliveries.DueDelivery> claimed =
                transactions.execute(status -> deliveries.claimed(send.deliveryId()));
        if (claimed == null || claimed.isEmpty()) {
            log.warn("stage 2: delivery {} - no longer SENDING under this claim; nothing sent", send.deliveryId());
            return;
        }
        JdbcDueDeliveries.DueDelivery due = claimed.get();
        RouteConfigRow row = due.interfaceId() == null || due.routeId() == null ? null
                : transactions.execute(status -> configRows.activeRoutes(tenantId, due.interfaceId()).stream()
                        .filter(candidate -> candidate.routeId().equals(due.routeId())).findFirst().orElse(null));
        if (row == null) {
            dropWithoutRoute(send, "route " + due.routeId() + " is not an ACTIVE route of its interface any more");
            return;
        }
        RouteInstance instance;
        try {
            instance = routes.ensure(row);
        } catch (RouteBuildRefusedException refused) {
            log.warn("stage 2: delivery {} - its route instance was refused: {}", send.deliveryId(),
                    refused.getMessage());
            recordPermanent(send);
            return;
        }
        routes.touch(instance, clock.get());
        Exchange done = producer.send(instance.consumerUri(), exchange -> exchange.getIn().setBody(send));
        if (done.getException() != null) {
            log.error("stage 2: delivery {} - the send ended outside layer 1's classified outcomes; it stays "
                    + "SENDING for the reconciler", send.deliveryId(), done.getException());
            return;
        }
        if (done.getProperty(Exchange.EXCEPTION_CAUGHT) == null) {
            transactions.executeWithoutResult(status -> outcomes.markDelivered(send.deliveryId(), send.claimToken()));
        }
    }

    private void dropWithoutRoute(Stage2Send send, String reason) {
        log.warn("stage 2: delivery {} - {}; dead-lettered PERMANENT", send.deliveryId(), reason);
        recordPermanent(send);
    }

    private void recordPermanent(Stage2Send send) {
        transactions.executeWithoutResult(status -> outcomes.recordFailure(send.deliveryId(), send.claimToken(), 0,
                OutcomeClass.PERMANENT, send.targetDeclaresIdempotentReceive()));
    }
}
