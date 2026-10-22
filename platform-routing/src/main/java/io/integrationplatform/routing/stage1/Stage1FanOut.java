package io.integrationplatform.routing.stage1;

import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkKind;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@code platform.message.accepted} work (T-049 composition of T-117/T-178; ADR-010 section 2 stage 1): under the
 * tenant the stage base resolved from the message row, in ONE transaction - read the message; a message no longer
 * {@code ACCEPTED} was routed by an earlier delivery of the same record (no effect); otherwise plan one delivery per
 * ACTIVE route of the interface version PINNED on the message, each pinning its route's mapping version, insert them
 * idempotently (the UNIQUE {@code (message_id, route_id, created_at)} triple, each with its due record in the same
 * commit) and move the message {@code ACCEPTED -> ROUTED}. Never calls a target.
 *
 * <p>ponytail: a route carrying a condition is not matched yet - conditional routing (T-117 conditions through
 * {@code RuleEvaluationPort}) and the stage-1 ordering-key lock (ADR-011 Decision 6, with the per-key counter of
 * E11 A2) are later slices; a conditional route is skipped with a WARN naming it, never delivered unconditionally.
 */
public final class Stage1FanOut implements StageConsumer.Handler {

    private static final Logger log = LoggerFactory.getLogger(Stage1FanOut.class);

    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final JdbcStage1Reads reads;
    private final Stage1Deliveries deliveries;
    private final RoutingStepRecorder recorder;

    public Stage1FanOut(WorkerTenantBinding binding, TransactionOperations transactions, JdbcStage1Reads reads,
            Stage1Deliveries deliveries) {
        this(binding, transactions, reads, deliveries, RoutingStepRecorder.NOOP);
    }

    public Stage1FanOut(WorkerTenantBinding binding, TransactionOperations transactions, JdbcStage1Reads reads,
            Stage1Deliveries deliveries, RoutingStepRecorder recorder) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public void onWork(WorkRecord record, UUID tenantId) {
        if (WorkKind.of(record.type()) != WorkKind.MESSAGE) {
            throw new IllegalArgumentException("stage 1 handles message.accepted records only, got: " + record.type());
        }
        binding.runAs(tenantId, () -> transactions.executeWithoutResult(status -> route(record.id())));
    }

    private void route(UUID messageId) {
        long loadStart = System.nanoTime();
        JdbcStage1Reads.PinnedMessage message = reads.message(messageId).orElse(null);
        if (message == null || !"ACCEPTED".equals(message.state())) {
            recorder.record(messageId, RoutingStep.LOAD, Duration.ofNanos(System.nanoTime() - loadStart));
            return; // routed already (a re-delivered record): acknowledged, no effect
        }
        recorder.record(messageId, RoutingStep.LOAD, Duration.ofNanos(System.nanoTime() - loadStart));
        long evaluateStart = System.nanoTime();
        String snapshot = reads.snapshotOf(message.interfaceVersionId()).orElseThrow(() -> new IllegalStateException(
                "message " + messageId + " pins interface version " + message.interfaceVersionId()
                        + ", which this tenant cannot read"));
        List<Stage1Delivery> planned = plan(message, FlowBundle.parse(snapshot));
        recorder.record(messageId, RoutingStep.EVALUATE_CONDITIONS, Duration.ofNanos(System.nanoTime() - evaluateStart));
        long createStart = System.nanoTime();
        deliveries.insertPerRoute(planned);
        deliveries.markMessageRouted(messageId);
        recorder.record(messageId, RoutingStep.CREATE_DELIVERIES, Duration.ofNanos(System.nanoTime() - createStart));
        log.info("stage 1: message {} routed into {} deliveries", messageId, planned.size());
    }

    /** One delivery per ACTIVE, unconditional route of the pinned snapshot, pinning that route's mapping version. */
    static List<Stage1Delivery> plan(JdbcStage1Reads.PinnedMessage message, FlowBundle.Parsed snapshot) {
        // the mapping version and its target contract version are read from the SAME snapshot mapping, so the pair
        // is the one the message was accepted under - the operator replay/retry pins both (BS-10)
        Map<String, FlowBundle.SnapshotMapping> mappingByRoute = new HashMap<>();
        for (FlowBundle.SnapshotMapping mapping : snapshot.mappings()) {
            if (mapping.mappingVersionId() != null) {
                mappingByRoute.putIfAbsent(mapping.routeId(), mapping);
            }
        }
        List<Stage1Delivery> planned = new ArrayList<>();
        for (FlowBundle.SnapshotRoute route : snapshot.routes()) {
            if (!"ACTIVE".equals(route.status())) {
                continue;
            }
            if (route.condition() != null && !route.condition().isBlank()) {
                log.warn("stage 1: route {} of message {} carries a condition - conditional routing is not composed"
                        + " yet, the route is not matched", route.id(), message.id());
                continue;
            }
            FlowBundle.SnapshotMapping pinned = mappingByRoute.get(route.id());
            if (pinned != null && pinned.targetContractVersionId() == null) {
                // T172: a flow snapshot published before e0b51120 carries no target contract id; a delivery pinning a
                // mapping version without its contract could never be replayed (BS-10) - refuse, never write a NULL pin
                throw new IllegalStateException("route " + route.id() + " of message " + message.id()
                        + " pins mapping version " + pinned.mappingVersionId() + " but the flow snapshot carries no"
                        + " target contract version id (published before the bundle gained it): republish the flow");
            }
            planned.add(new Stage1Delivery(message.id(), UUID.fromString(route.id()), message.acceptedAt(),
                    UUID.fromString(route.targetEndpointId()),
                    pinned == null ? null : UUID.fromString(pinned.mappingVersionId()),
                    pinned == null || pinned.targetContractVersionId() == null ? null
                            : UUID.fromString(pinned.targetContractVersionId()),
                    null, message.interfaceId(), message.businessKey(), message.keySeq()));
        }
        return planned;
    }
}
