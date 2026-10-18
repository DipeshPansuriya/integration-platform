package io.integrationplatform.app.audit;

import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E12 N3 (T-067 narrow), row278 D2/M3: the audit writer - the Spring event subscriber the {@link AuditEvent} seam
 * was shaped for. It subscribes BEFORE_COMMIT and appends to the transactional outbox ({@code public.audit_outbox})
 * INSIDE the business transaction: the audit row commits or rolls back with the business action, so a committed
 * action can never lose its audit row to a crash or a failed second transaction (the after-commit write it
 * replaces could). After the commit, {@link #onCommitted(AuditEvent)} relays the bound tenant's pending rows into
 * the append-only ledger in a new transaction; a failed relay is logged and the rows wait for
 * {@link AuditOutboxRelay}.
 *
 * <p>Fails closed on identity: the event's tenant must equal the tenant bound to the unit of work
 * ({@link TenantContext}) - a forged or mismatched event, or one with no tenant bound, is refused with an ERROR log
 * and NOT persisted under anyone's tenant. A failing outbox append throws and rolls the business back (the audit
 * is part of the action).
 *
 * <p>Plain class, wired by {@link AuditPersistenceConfiguration}; {@link #record(AuditEvent)} is the listener body
 * without the event annotation.
 */
public final class AuditEventWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AuditEventWriter.class);

    /** Pending rows one after-commit relay moves; a backlog beyond it is the relay loop's. */
    static final int RELAY_BATCH = 50;

    private final AuditOutbox outbox;
    private final TransactionOperations relayTransaction;
    private final TenantContext tenantContext;

    AuditEventWriter(AuditOutbox outbox, TransactionOperations relayTransaction, TenantContext tenantContext) {
        this.outbox = outbox;
        this.relayTransaction = relayTransaction;
        this.tenantContext = tenantContext;
    }

    /** The subscription: every {@link AuditEvent} is appended to the outbox in the publishing transaction. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAuditEvent(AuditEvent event) {
        record(event);
    }

    /** After the business commit: move the bound tenant's pending outbox rows into the ledger. Never throws. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(AuditEvent event) {
        if (tenantContext.currentTenantId().isEmpty()) {
            return;
        }
        try {
            relayTransaction.execute(status -> outbox.relayBound(RELAY_BATCH));
        } catch (RuntimeException e) {
            // the committed outbox row is durable and the relay loop retries it; e.toString() only, no chain
            LOG.warn("audit relay after commit of {} failed - the outbox row waits for the relay loop: {}",
                    event.action(), e.toString());
        }
    }

    void record(AuditEvent event) {
        Optional<UUID> bound = tenantContext.currentTenantId();
        if (bound.isEmpty()) {
            LOG.error("audit event {} from actor {} refused: no tenant bound to the unit of work"
                    + " (the ledger records accountable events only)", event.action(), event.who());
            return;
        }
        if (!bound.get().equals(event.tenantId())) {
            LOG.error("audit event {} from actor {} refused: it names tenant {} but the transaction"
                    + " is bound to tenant {}", event.action(), event.who(), event.tenantId(), bound.get());
            return;
        }
        outbox.append(event);
    }
}
