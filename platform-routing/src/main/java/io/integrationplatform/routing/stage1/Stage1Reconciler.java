package io.integrationplatform.routing.stage1;

import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/** T-180 routing leg - RED stub. */
public final class Stage1Reconciler {

    public Stage1Reconciler(WorkerTenantBinding binding, TransactionOperations transactions, JdbcStage1Reads reads,
            StageConsumer.Handler stage1, Duration stuckAfter, Supplier<Instant> clock) {
        Objects.requireNonNull(binding);
    }

    public int sweepOnce(UUID tenantId) {
        return 0;
    }
}
