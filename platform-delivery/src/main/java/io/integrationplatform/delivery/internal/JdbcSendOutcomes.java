package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

    public JdbcSendOutcomes(JdbcClient jdbcClient, TenantContext tenantContext) {
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
        this.recorder = new OutcomeRecorder(new JdbcDeliveryRetryStore(jdbcClient, tenantContext),
                RetryPolicy.defaults(), () -> ThreadLocalRandom.current().nextDouble());
    }

    @Override
    public void recordAttempt(UUID deliveryId, int layer, OutcomeClass outcome, Instant startedAt,
            Instant finishedAt, String detail) {
        repository.recordAttempt(deliveryId, layer, outcome, startedAt, finishedAt, detail);
    }

    @Override
    public boolean markDelivered(UUID deliveryId, UUID claimToken) {
        return repository.markDelivered(deliveryId, claimToken, Instant.now());
    }

    @Override
    public void recordFailure(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome,
            boolean targetDeclaresIdempotentReceive) {
        recorder.record(deliveryId, claimToken, attemptsSoFar, outcome, Instant.now(), null,
                targetDeclaresIdempotentReceive);
    }
}
