package io.integrationplatform.app.scheduler;

import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.routing.callback.CallbackDispatcher;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The body of CALLBACK_SEND (IB08 Phase 3, ADR-012 register extension): walks active tenants and
 * dispatches SCHEDULED-mode callback responses to their configured callback URLs. Each tenant is
 * bound on the worker thread for its pass, same pattern as {@link CredentialCleanupHandler}. One
 * tenant's failure is logged and the others continue; the run then fails so the failed tenant is
 * retried at the next run.
 */
@Component
public class CallbackSendHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackSendHandler.class);
    private static final int BATCH_SIZE = 50;

    private final ActiveTenantDirectory activeTenants;
    private final WorkerTenantBinding binding;
    private final CallbackDispatcher dispatcher;

    @Autowired
    public CallbackSendHandler(ActiveTenantDirectory activeTenants, ObjectProvider<WorkerTenantBinding> binding,
            CallbackDispatcher dispatcher) {
        this(activeTenants, binding.getIfAvailable(), dispatcher);
    }

    CallbackSendHandler(ActiveTenantDirectory activeTenants, WorkerTenantBinding binding,
            CallbackDispatcher dispatcher) {
        this.activeTenants = Objects.requireNonNull(activeTenants, "activeTenants");
        this.binding = binding;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public String type() {
        return SchedulerJobTasks.CALLBACK_SEND;
    }

    @Override
    public void run(String jobData) {
        if (binding == null) {
            throw new IllegalStateException("CALLBACK_SEND needs a WorkerTenantBinding to bind each tenant;"
                    + " this composition has none (IPASS_KAFKA_BOOTSTRAP_SERVERS unset) - nothing was sent");
        }
        if (binding.currentTenantId().isPresent()) {
            throw new IllegalStateException("CALLBACK_SEND refuses a thread already bound to a tenant");
        }
        int tenants = 0;
        int failed = 0;
        int totalSent = 0;
        for (UUID tenant : activeTenants.activeTenantIds()) {
            tenants++;
            try {
                int sent = binding.callAs(tenant, () -> dispatchForTenant(tenant));
                totalSent += sent;
            } catch (RuntimeException failure) {
                failed++;
                LOG.atWarn().addKeyValue("event", "callback.send_failed").addKeyValue("tenant", tenant)
                        .setCause(failure).log("CALLBACK_SEND failed for tenant {}", tenant);
            }
        }
        LOG.atInfo().addKeyValue("event", "callback.send.complete")
                .addKeyValue("tenants", tenants).addKeyValue("sent", totalSent).addKeyValue("failed", failed)
                .log("CALLBACK_SEND complete: {} tenants, {} callbacks sent, {} failures", tenants, totalSent, failed);
        if (failed > 0) {
            throw new IllegalStateException("callback send failed for " + failed + " of " + tenants
                    + " tenants; they are retried at the next run");
        }
    }

    private int dispatchForTenant(UUID tenant) {
        int total = 0;
        int dispatched;
        do {
            dispatched = dispatcher.dispatch("SCHEDULED", BATCH_SIZE);
            total += dispatched;
        } while (dispatched == BATCH_SIZE);
        return total;
    }
}
