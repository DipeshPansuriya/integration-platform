package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** row278 D2/M3: the relay drains each active tenant's outbox under that tenant's binding. */
class AuditOutboxRelayTest {

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");
    private static final UUID TENANT_C = UUID.fromString("0000000c-0000-4000-8000-00000000000c");

    private final WorkerTenantBinding binding = new WorkerTenantBinding();
    private final List<UUID> relayedFor = new ArrayList<>();

    private AuditOutboxRelay relay(UUID failing) {
        AuditOutbox outbox = new AuditOutbox() {
            @Override
            public void append(io.integrationplatform.domain.audit.AuditEvent event) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int relayBound(int limit) {
                UUID tenant = binding.requireTenantId();
                if (tenant.equals(failing)) {
                    throw new IllegalStateException("tenant " + tenant + " relay failed");
                }
                relayedFor.add(tenant);
                return 2;
            }
        };
        return new AuditOutboxRelay(() -> List.of(TENANT_A, TENANT_B, TENANT_C), binding,
                TransactionOperations.withoutTransaction(), outbox, 50);
    }

    @Test
    void eachActiveTenantIsRelayedUnderItsOwnBindingAndTheMovedRowsAreSummed() {
        int moved = relay(null).runOnce();

        assertThat(relayedFor).containsExactly(TENANT_A, TENANT_B, TENANT_C);
        assertThat(moved).isEqualTo(6);
        assertThat(binding.currentTenantId()).as("no binding leaks past the cycle").isEmpty();
    }

    @Test
    void oneFailingTenantDoesNotStopTheOthers() {
        int moved = relay(TENANT_B).runOnce();

        assertThat(relayedFor).containsExactly(TENANT_A, TENANT_C);
        assertThat(moved).isEqualTo(4);
        assertThat(binding.currentTenantId()).isEmpty();
    }
}
