package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

/**
 * row278 D2/M3: the audit write rides the business transaction (an outbox row appended BEFORE_COMMIT), so a
 * committed business action can no longer lose its audit row after commit; the after-commit relay is best-effort.
 */
class AuditEventWriterTest {

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private final WorkerTenantBinding binding = new WorkerTenantBinding();
    private final RecordingOutbox outbox = new RecordingOutbox(binding);
    private final AuditEventWriter writer =
            new AuditEventWriter(outbox, TransactionOperations.withoutTransaction(), binding);

    private static AuditEvent event(UUID tenant) {
        return new AuditEvent(tenant, "admin", "LOOKUP_ENTRY_CHANGED", List.of("lookup:1"), null, null,
                Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void theOutboxAppendRunsInsideTheBusinessTransactionBeforeCommit() throws NoSuchMethodException {
        TransactionalEventListener listener = AuditEventWriter.class
                .getMethod("onAuditEvent", AuditEvent.class).getAnnotation(TransactionalEventListener.class);
        assertThat(listener.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
        TransactionalEventListener relay = AuditEventWriter.class
                .getMethod("onCommitted", AuditEvent.class).getAnnotation(TransactionalEventListener.class);
        assertThat(relay.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void anEventForTheBoundTenantIsAppendedToTheOutbox() {
        AuditEvent event = event(TENANT_A);
        binding.runAs(TENANT_A, () -> writer.onAuditEvent(event));
        assertThat(outbox.appended).containsExactly(event);
    }

    @Test
    void anUnboundOrMismatchedEventIsRefusedAndNothingIsAppended() {
        writer.onAuditEvent(event(TENANT_A));
        binding.runAs(TENANT_A, () -> writer.onAuditEvent(event(TENANT_B)));
        assertThat(outbox.appended).isEmpty();
    }

    @Test
    void afterCommitTheBoundTenantsOutboxIsRelayed() {
        binding.runAs(TENANT_A, () -> writer.onCommitted(event(TENANT_A)));
        assertThat(outbox.relayedFor).containsExactly(TENANT_A);
    }

    @Test
    void aFailedAfterCommitRelayNeverSurfacesOnTheCommittedCaller() {
        outbox.failRelay = true;
        assertThatCode(() -> binding.runAs(TENANT_A, () -> writer.onCommitted(event(TENANT_A))))
                .doesNotThrowAnyException();
    }

    @Test
    void afterCommitWithNoTenantBoundRelaysNothing() {
        writer.onCommitted(event(TENANT_A));
        assertThat(outbox.relayedFor).isEmpty();
    }

    static final class RecordingOutbox implements AuditOutbox {

        private final WorkerTenantBinding binding;
        final List<AuditEvent> appended = new ArrayList<>();
        final List<UUID> relayedFor = new ArrayList<>();
        boolean failRelay;

        RecordingOutbox(WorkerTenantBinding binding) {
            this.binding = binding;
        }

        @Override
        public void append(AuditEvent event) {
            appended.add(event);
        }

        @Override
        public int relayBound(int limit) {
            if (failRelay) {
                throw new IllegalStateException("relay down");
            }
            relayedFor.add(binding.requireTenantId());
            return 1;
        }
    }
}
