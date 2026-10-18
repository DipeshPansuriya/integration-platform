package io.integrationplatform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-049: a worker's tenant binding lasts exactly its unit of work and never switches tenants on one thread. */
class WorkerTenantBindingTest {

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private final WorkerTenantBinding binding = new WorkerTenantBinding();

    @Test
    @DisplayName("the tenant is bound for the work only - nothing before, nothing after")
    void boundForTheWorkOnly() {
        assertThat(binding.currentTenantId()).isEmpty();
        UUID seen = binding.callAs(TENANT_A, () -> binding.currentTenantId().orElseThrow());
        assertThat(seen).isEqualTo(TENANT_A);
        assertThat(binding.currentTenantId()).as("unbound after the work").isEmpty();
    }

    @Test
    @DisplayName("a failing unit of work still unbinds")
    void failingWorkUnbinds() {
        assertThatThrownBy(() -> binding.runAs(TENANT_A, () -> {
            throw new IllegalStateException("the work failed");
        })).hasMessage("the work failed");
        assertThat(binding.currentTenantId()).isEmpty();
    }

    @Test
    @DisplayName("a nested binding may repeat the tenant but never switch it; the outer binding survives the inner")
    void nestedBindingNeverSwitchesTenants() {
        binding.runAs(TENANT_A, () -> {
            binding.runAs(TENANT_A, () -> assertThat(binding.currentTenantId()).contains(TENANT_A));
            assertThat(binding.currentTenantId()).as("the outer binding stands").contains(TENANT_A);
            assertThatThrownBy(() -> binding.runAs(TENANT_B, () -> { }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(binding.currentTenantId()).contains(TENANT_A);
        });
        assertThat(binding.currentTenantId()).isEmpty();
    }

    @Test
    @DisplayName("the binding is thread-confined: another thread sees nothing")
    void threadConfined() throws Exception {
        // row 278 T158: the other thread's view is captured and asserted on the test thread - an assertion inside
        // the spawned thread would only kill that thread and could never fail the test.
        AtomicReference<Optional<UUID>> seenByOther = new AtomicReference<>();
        binding.runAs(TENANT_A, () -> {
            Thread other = new Thread(() -> seenByOther.set(binding.currentTenantId()));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while joining the other thread", e);
            }
        });
        assertThat(seenByOther.get()).as("the other thread ran and saw no tenant").isNotNull().isEmpty();
    }
}
