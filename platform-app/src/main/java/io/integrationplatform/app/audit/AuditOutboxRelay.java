package io.integrationplatform.app.audit;

import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * row278 D2/M3: the audit outbox relay - one cycle drains each active tenant's pending outbox rows into the ledger,
 * one tenant per transaction under {@link WorkerTenantBinding#callAs}. A failing tenant is logged and skipped; the
 * others still relay.
 */
final class AuditOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(AuditOutboxRelay.class);

    private final ActiveTenantDirectory activeTenants;
    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final AuditOutbox outbox;
    private final int batch;

    AuditOutboxRelay(ActiveTenantDirectory activeTenants, WorkerTenantBinding binding,
            TransactionOperations transactions, AuditOutbox outbox, int batch) {
        this.activeTenants = Objects.requireNonNull(activeTenants, "activeTenants");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.batch = batch;
    }

    /** One cycle over every active tenant; returns the rows moved into the ledger. */
    int runOnce() {
        int moved = 0;
        for (UUID tenant : activeTenants.activeTenantIds()) {
            try {
                Integer rows = binding.callAs(tenant, () -> transactions.execute(s -> outbox.relayBound(batch)));
                moved += rows == null ? 0 : rows;
            } catch (RuntimeException e) {
                LOG.warn("audit relay for tenant {} failed - its rows wait for the next cycle: {}", tenant,
                        e.toString());
            }
        }
        return moved;
    }
}
