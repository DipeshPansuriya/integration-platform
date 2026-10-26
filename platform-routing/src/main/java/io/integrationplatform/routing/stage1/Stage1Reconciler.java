package io.integrationplatform.routing.stage1;

import io.integrationplatform.delivery.port.UnroutedMessageSweep;
import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The routing leg of the T-180 reconciler (ADR fix m-817272 B4; ADR-010 section 9): a message still {@code ACCEPTED}
 * past {@code stuckAfter} lost its {@code message.accepted} record somewhere (a broker outage, a poisoned partition),
 * so one sweep re-hands each such message of the tenant to stage 1 as if the record had arrived again. Idempotent by
 * construction: stage 1's {@code ACCEPTED -> ROUTED} guard and the UNIQUE {@code (message_id, route_id, created_at)}
 * triple make a re-hand of a message that a live consumer routes meanwhile a no-op. One message failing is logged and
 * left {@code ACCEPTED} for the next sweep; it never stops the others.
 *
 * <p>No timer here (ADR-012 section 3, SchedulerTimingRulesTest): the caller - the delivery-retry trigger's tenant
 * loop, through the delivery module's {@link UnroutedMessageSweep} port which this class implements and
 * {@code PipelineCompositionConfiguration} wires (T72) - calls {@link #sweepOnce(UUID)} once per active tenant.
 */
public final class Stage1Reconciler implements UnroutedMessageSweep {

    private static final Logger log = LoggerFactory.getLogger(Stage1Reconciler.class);

    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final JdbcStage1Reads reads;
    private final StageConsumer.Handler stage1;
    private final Duration stuckAfter;
    private final Supplier<Instant> clock;

    public Stage1Reconciler(WorkerTenantBinding binding, TransactionOperations transactions, JdbcStage1Reads reads,
            StageConsumer.Handler stage1, Duration stuckAfter, Supplier<Instant> clock) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.stage1 = Objects.requireNonNull(stage1, "stage1");
        this.stuckAfter = Objects.requireNonNull(stuckAfter, "stuckAfter");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (stuckAfter.isNegative() || stuckAfter.isZero()) {
            throw new IllegalArgumentException("stuckAfter must be positive");
        }
    }

    /**
     * One sweep for one tenant.
     *
     * @return how many stuck messages were re-handed to stage 1 without an error
     */
    @Override
    public int sweepOnce(UUID tenantId) {
        Instant cutoff = clock.get().minus(stuckAfter);
        List<UUID> stuck = binding.callAs(tenantId, () -> transactions.execute(status -> reads.acceptedBefore(cutoff)));
        int reHanded = 0;
        for (UUID messageId : stuck == null ? List.<UUID>of() : stuck) {
            try {
                stage1.onWork(new WorkRecord(1, "message.accepted", messageId, null), tenantId);
                reHanded++;
            } catch (RuntimeException e) {
                log.warn("reconciler: message {} stays ACCEPTED for the next sweep", messageId, e);
            }
        }
        if (reHanded > 0) {
            log.info("reconciler: {} message(s) stuck ACCEPTED re-handed to stage 1", reHanded);
        }
        return reHanded;
    }
}
