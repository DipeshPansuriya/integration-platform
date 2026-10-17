package io.integrationplatform.app.scheduler;

import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.api.CredentialCleanup;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * E14 T-203 (ADR-007 Decision 3; ADR-012 :129, :165, :269): the body of CREDENTIAL_CLEANUP. Walks the tenants of
 * {@code ipaas_active_tenants()} and runs one {@link CredentialCleanup} pass per tenant, that tenant bound on the
 * worker thread for exactly its pass. The tenant never comes from the job data (ignored - a job-definition row never
 * holds a tenant id) nor from a binding already on the thread (refused before any work).
 *
 * <p>One tenant's failure is logged and the others continue; the run then fails (one FAILED job_run, one alert) so the
 * failed tenant is retried at the next run. A composition without a {@link WorkerTenantBinding} (no Kafka bootstrap)
 * cannot bind a tenant, so the run fails loudly rather than silently leaving stale credentials.
 */
@Component
public class CredentialCleanupHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialCleanupHandler.class);

    private final ActiveTenantDirectory activeTenants;
    private final WorkerTenantBinding binding;
    private final CredentialCleanup cleanup;

    @Autowired
    public CredentialCleanupHandler(ActiveTenantDirectory activeTenants, ObjectProvider<WorkerTenantBinding> binding,
            CredentialCleanup cleanup) {
        this(activeTenants, binding.getIfAvailable(), cleanup);
    }

    CredentialCleanupHandler(ActiveTenantDirectory activeTenants, WorkerTenantBinding binding,
            CredentialCleanup cleanup) {
        this.activeTenants = Objects.requireNonNull(activeTenants, "activeTenants");
        this.binding = binding;
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    @Override
    public String type() {
        return SchedulerJobTasks.CREDENTIAL_CLEANUP;
    }

    @Override
    public void run(String jobData) {
        if (binding == null) {
            throw new IllegalStateException("CREDENTIAL_CLEANUP needs a WorkerTenantBinding to bind each tenant;"
                    + " this composition has none (IPASS_KAFKA_BOOTSTRAP_SERVERS unset) - nothing was cleaned");
        }
        if (binding.currentTenantId().isPresent()) {
            throw new IllegalStateException("CREDENTIAL_CLEANUP refuses a thread already bound to a tenant - each"
                    + " tenant comes from ipaas_active_tenants() only");
        }
        int tenants = 0;
        int failed = 0;
        for (UUID tenant : activeTenants.activeTenantIds()) {
            tenants++;
            try {
                CredentialCleanup.Result result = binding.callAs(tenant, cleanup::cleanUpBoundTenant);
                LOG.atInfo().addKeyValue("event", "credential.cleanup").addKeyValue("tenant", tenant)
                        .addKeyValue("abandoned", result.abandoned()).addKeyValue("expired", result.expired())
                        .addKeyValue("cleared", result.cleared()).addKeyValue("still_pending", result.stillPending())
                        .addKeyValue("waiting", result.waiting())
                        .log("event=credential.cleanup tenant={} {}", tenant, result);
            } catch (RuntimeException failure) {
                failed++;
                LOG.atWarn().addKeyValue("event", "credential.cleanup_failed").addKeyValue("tenant", tenant)
                        .setCause(failure).log("event=credential.cleanup_failed tenant={}", tenant);
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("credential clean-up failed for " + failed + " of " + tenants
                    + " tenants; they are retried at the next run");
        }
    }
}
