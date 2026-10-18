package io.integrationplatform.app.audit;

import io.integrationplatform.domain.audit.AuditEvent;

/**
 * row278 D2/M3: the transactional audit outbox ({@code public.audit_outbox}, V2_4_3). Both operations run for the
 * tenant bound to the current unit of work, inside the caller's transaction.
 */
interface AuditOutbox {

    /** Appends the event as one pending outbox row in the caller's (business) transaction. */
    void append(AuditEvent event);

    /** Moves up to {@code limit} of the bound tenant's pending rows into the audit ledger; returns how many. */
    int relayBound(int limit);
}
