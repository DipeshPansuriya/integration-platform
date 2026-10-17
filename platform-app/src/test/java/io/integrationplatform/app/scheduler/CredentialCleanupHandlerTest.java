package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.api.CredentialCleanup;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row 327 T158 (E14 T-203 + T-144; ADR-012 :129, :165, :269; ADR-007 Decision 3): the CREDENTIAL_CLEANUP body walks
 * the tenants of {@code ipaas_active_tenants()} and binds ONE tenant per unit of work; the tenant never comes from the
 * job data or from a binding already on the thread.
 *
 * <pre>
 *   Given active tenants A and B
 *   When  the job runs with job data that names a forged tenant (JSON or a bare UUID)
 *   Then  the clean-up runs once per active tenant, bound to that tenant, and never for the forged one
 *
 *   Given a worker thread already bound to a forged tenant
 *   When  the job runs on it
 *   Then  it refuses before any clean-up runs (a pre-bound tenant is never used)
 *
 *   Given tenant A's clean-up fails
 *   When  the job runs for A, B and C
 *   Then  B and C are still cleaned, and the run fails (FAILED + one alert) so A is retried next run
 * </pre>
 */
class CredentialCleanupHandlerTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TENANT_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID FORGED = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private final WorkerTenantBinding binding = new WorkerTenantBinding();

    @Test
    void itIsTheCredentialCleanupJobType() {
        RecordingCleanup cleanup = new RecordingCleanup(Set.of());
        assertThat(handler(List.of(), cleanup).type()).isEqualTo(SchedulerJobTasks.CREDENTIAL_CLEANUP);
    }

    @Test
    void aTenantSmuggledInThroughJobDataIsNeverUsed() {
        for (String jobData : List.of("", "{\"tenantId\":\"" + FORGED + "\"}", FORGED.toString(),
                "tenant_id=" + FORGED)) {
            RecordingCleanup cleanup = new RecordingCleanup(Set.of());

            handler(List.of(TENANT_A, TENANT_B), cleanup).run(jobData);

            assertThat(cleanup.boundTenants).as("job data %s", jobData).containsExactly(TENANT_A, TENANT_B);
            assertThat(cleanup.boundTenants).doesNotContain(FORGED);
            assertThat(binding.currentTenantId()).as("nothing stays bound after the run").isEmpty();
        }
    }

    @Test
    void aForgedTenantAlreadyBoundOnTheThreadIsRefusedBeforeAnyCleanUp() {
        RecordingCleanup cleanup = new RecordingCleanup(Set.of());
        CredentialCleanupHandler handler = handler(List.of(TENANT_A, TENANT_B), cleanup);

        binding.runAs(FORGED, () -> assertThatThrownBy(() -> handler.run(""))
                .isInstanceOf(IllegalStateException.class));

        assertThat(cleanup.boundTenants).as("no clean-up ran under the forged or any other tenant").isEmpty();
        assertThat(binding.currentTenantId()).isEmpty();
    }

    @Test
    void oneTenantsFailureDoesNotStopTheOthersAndFailsTheRunForTheRetry() {
        RecordingCleanup cleanup = new RecordingCleanup(Set.of(TENANT_A));

        assertThatThrownBy(() -> handler(List.of(TENANT_A, TENANT_B, TENANT_C), cleanup).run(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 of 3");

        assertThat(cleanup.boundTenants).containsExactly(TENANT_A, TENANT_B, TENANT_C);
        assertThat(cleanup.succeeded).containsExactly(TENANT_B, TENANT_C);
        assertThat(binding.currentTenantId()).isEmpty();
    }

    @Test
    void noActiveTenantMeansNoCleanUpAndASuccessfulRun() {
        RecordingCleanup cleanup = new RecordingCleanup(Set.of());

        handler(List.of(), cleanup).run("");

        assertThat(cleanup.boundTenants).isEmpty();
    }

    @Test
    void aCompositionWithoutAWorkerTenantBindingFailsLoudInsteadOfSkippingSilently() {
        RecordingCleanup cleanup = new RecordingCleanup(Set.of());
        CredentialCleanupHandler handler = new CredentialCleanupHandler(() -> List.of(TENANT_A),
                (WorkerTenantBinding) null, cleanup);

        assertThatThrownBy(() -> handler.run("")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WorkerTenantBinding");
        assertThat(cleanup.boundTenants).isEmpty();
    }

    private CredentialCleanupHandler handler(List<UUID> activeTenants, CredentialCleanup cleanup) {
        ActiveTenantDirectory directory = () -> activeTenants;
        return new CredentialCleanupHandler(directory, binding, cleanup);
    }

    /** Records the tenant bound on the thread at each call - the only tenant the store would ever see. */
    private final class RecordingCleanup implements CredentialCleanup {

        private final Set<UUID> failFor;
        private final List<UUID> boundTenants = new ArrayList<>();
        private final List<UUID> succeeded = new ArrayList<>();

        RecordingCleanup(Set<UUID> failFor) {
            this.failFor = failFor;
        }

        @Override
        public Result cleanUpBoundTenant() {
            UUID bound = binding.currentTenantId().orElseThrow(() -> new AssertionError("no tenant bound"));
            boundTenants.add(bound);
            if (failFor.contains(bound)) {
                throw new IllegalStateException("database unavailable for " + bound);
            }
            succeeded.add(bound);
            return new Result(0, 0, 0, 0, 0);
        }
    }
}
