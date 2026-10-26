package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SendOutcomes} on lane C's delivery tables (T-049 composition): the attempt append and the success move are
 * {@link JdbcDeliveryRepository}'s, the failure move is the existing {@link OutcomeRecorder} over the retry store with
 * the team-default {@link RetryPolicy} (8 attempts, 30 s base, 1 h ceiling, full jitter) - nothing new decides a state
 * here, the composition only reaches the machinery that already does.
 */
public final class JdbcSendOutcomes implements SendOutcomes {

    private final JdbcDeliveryRepository repository;
    private final OutcomeRecorder recorder;
    private final StageTimings timings;

    /** Records no stage timing - for a database without the message family (this module's own tests). */
    public JdbcSendOutcomes(JdbcClient jdbcClient, TenantContext tenantContext) {
        this(jdbcClient, tenantContext, null);
    }

    /**
     * T105 wiring: the success move records the message's DELIVER stage, and a dead letter through the recorder
     * does too, each in the caller's transaction (the writer takes the tenant from its own stamped context).
     */
    public JdbcSendOutcomes(JdbcClient jdbcClient, TenantContext tenantContext, StageTimings timings) {
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
        JdbcDeliveryRetryStore store = new JdbcDeliveryRetryStore(jdbcClient, tenantContext);
        DoubleSupplier jitter = () -> ThreadLocalRandom.current().nextDouble();
        this.recorder = timings == null ? new OutcomeRecorder(store, RetryPolicy.defaults(), jitter)
                : new OutcomeRecorder(store, RetryPolicy.defaults(), jitter, timings);
        this.timings = timings;
    }

    JdbcSendOutcomes(JdbcDeliveryRepository repository, OutcomeRecorder recorder, StageTimings timings) {
        this.repository = repository;
        this.recorder = recorder;
        this.timings = timings;
    }

    @Override
    public void recordAttempt(UUID deliveryId, int layer, OutcomeClass outcome, Instant startedAt,
            Instant finishedAt, String detail) {
        repository.recordAttempt(deliveryId, layer, outcome, startedAt, finishedAt, detail);
    }

    @Override
    public boolean markDelivered(UUID deliveryId, UUID claimToken) {
        Instant at = Instant.now();
        boolean delivered = repository.markDelivered(deliveryId, claimToken, at);
        if (delivered && timings != null) {
            // E13: the message's DELIVER stage, finished at this move, in the move's transaction; a redelivered
            // record matches nothing here (0 rows), so it writes no second timing, and the writer's ON CONFLICT
            // keeps one row per message and stage
            timings.recordDeliverFinished(deliveryId, at);
        }
        return delivered;
    }

    @Override
    public void recordFailure(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome,
            boolean targetDeclaresIdempotentReceive) {
        // T165-B (ADR-010 s6 M5): the budget is the ledger's current-series count, not the caller's value - the
        // layer-1 route hands over its per-exchange try count, which neither spans re-drives nor resets on replay.
        // recordAttempt writes this call's row before the failure is recorded, so the count includes it.
        recorder.record(deliveryId, claimToken, repository.countAttempts(deliveryId), outcome, Instant.now(), null,
                targetDeclaresIdempotentReceive);
    }

    @Override
    public boolean deferCircuitOpen(UUID deliveryId, UUID claimToken, Instant nextAttemptAt) {
        Instant now = Instant.now();
        if (repository.deferCircuitOpen(deliveryId, claimToken, nextAttemptAt, now)) {
            return true;
        }
        repository.appendEvent(deliveryId, DeliveryEventType.LATE_OUTCOME,
                "circuit-open deferral on a stale or foreign claim", now);
        return false;
    }
}
