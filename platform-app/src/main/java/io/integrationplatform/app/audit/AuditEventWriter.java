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
 * E12 N3 (T-067 narrow): the append-only audit writer - the Spring event subscriber the {@link AuditEvent} seam
 * was shaped for ("E12's append-only writer is the subscriber that persists it", the record's javadoc). It
 * subscribes AFTER_COMMIT: an audit row exists exactly for business actions that COMMITTED - a rolled-back
 * transaction publishes nothing (done_when 1) - and the ledger append runs in its own new transaction on the
 * tenant-stamped connection (R11).
 *
 * <p>Fails closed on identity, never on the business path: the event's tenant must equal the tenant bound to the
 * committing unit of work ({@link TenantContext}) - a forged or mismatched event is refused with an ERROR log and
 * NOT persisted under anyone's tenant, and the already-committed business transaction is left alone (throwing
 * from an after-commit callback would surface on the caller after success). With no tenant bound (an off-request
 * publisher without a context) the same refusal applies: the ledger records accountable events only.
 *
 * <p>Plain class, wired by {@link AuditPersistenceConfiguration}; the test-facing {@link #record(AuditEvent)} is
 * the listener body without the event annotation.
 */
public final class AuditEventWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AuditEventWriter.class);

    private final JdbcAuditEventStore store;
    private final TransactionOperations ledgerTransaction;
    private final TenantContext tenantContext;

    AuditEventWriter(JdbcAuditEventStore store, TransactionOperations ledgerTransaction,
            TenantContext tenantContext) {
        this.store = store;
        this.ledgerTransaction = ledgerTransaction;
        this.tenantContext = tenantContext;
    }

    /** The subscription: every committed {@link AuditEvent} reaches the ledger exactly once. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuditEvent(AuditEvent event) {
        record(event);
    }

    void record(AuditEvent event) {
        Optional<UUID> bound = tenantContext.currentTenantId();
        if (bound.isEmpty()) {
            LOG.error("audit event {} from actor {} refused: no tenant bound to the committing unit of work"
                    + " (the ledger records accountable events only)", event.action(), event.who());
            return;
        }
        if (!bound.get().equals(event.tenantId())) {
            LOG.error("audit event {} from actor {} refused: it names tenant {} but the committing transaction"
                    + " is bound to tenant {}", event.action(), event.who(), event.tenantId(), bound.get());
            return;
        }
        ledgerTransaction.executeWithoutResult(status -> store.append(event));
    }
}
