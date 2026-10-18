package io.integrationplatform.routing.stage1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** T-180 routing leg (ADR fix m-817272 B4): ACCEPTED-not-ROUTED messages past the threshold go back to stage 1. */
class Stage1ReconcilerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final WorkerTenantBinding binding = new WorkerTenantBinding();
    private final List<UUID> stuck = new ArrayList<>();
    private final AtomicReference<Instant> cutoffAsked = new AtomicReference<>();
    private final AtomicReference<UUID> tenantDuringRead = new AtomicReference<>();
    private final JdbcStage1Reads reads = new JdbcStage1Reads(mock(JdbcClient.class), binding) {
        @Override
        public List<UUID> acceptedBefore(Instant cutoff) {
            cutoffAsked.set(cutoff);
            tenantDuringRead.set(binding.currentTenantId().orElse(null));
            return List.copyOf(stuck);
        }
    };
    private final List<WorkRecord> handed = new ArrayList<>();
    private final List<UUID> handedTenants = new ArrayList<>();

    private Stage1Reconciler reconciler(UUID failing) {
        return new Stage1Reconciler(binding, TransactionOperations.withoutTransaction(), reads, (record, tenantId) -> {
            if (record.id().equals(failing)) {
                throw new IllegalStateException("boom");
            }
            handed.add(record);
            handedTenants.add(tenantId);
        }, STUCK_AFTER, () -> NOW);
    }

    @Test
    @DisplayName("each message stuck ACCEPTED past the threshold is re-handed to stage 1 as message.accepted work")
    void reHandsStuckMessages() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stuck.addAll(List.of(a, b));

        int swept = reconciler(null).sweepOnce(TENANT);

        assertThat(swept).isEqualTo(2);
        assertThat(cutoffAsked.get()).isEqualTo(NOW.minus(STUCK_AFTER));
        assertThat(tenantDuringRead.get()).isEqualTo(TENANT);
        assertThat(handed).extracting(WorkRecord::id).containsExactly(a, b);
        assertThat(handed).extracting(WorkRecord::type).containsOnly("message.accepted");
        assertThat(handedTenants).containsOnly(TENANT);
    }

    @Test
    @DisplayName("one message failing never stops the rest; it stays ACCEPTED for the next sweep")
    void oneFailureDoesNotBlockTheOthers() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        stuck.addAll(List.of(bad, good));

        int swept = reconciler(bad).sweepOnce(TENANT);

        assertThat(swept).isEqualTo(1);
        assertThat(handed).extracting(WorkRecord::id).containsExactly(good);
    }

    @Test
    @DisplayName("nothing stuck - nothing handed")
    void nothingStuck() {
        assertThat(reconciler(null).sweepOnce(TENANT)).isZero();
        assertThat(handed).isEmpty();
    }
}
