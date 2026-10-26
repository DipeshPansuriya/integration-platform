package io.integrationplatform.audit.api;

/**
 * E12 seam (arjun-fullstack): the append-only WRITE side of the tenant activity view. The tenant is never a
 * parameter: it is the one bound to the caller's unit of work (R08), and the record runs inside the caller's
 * tenant-stamped transaction, so it commits or rolls back with the business action. APPENDS ONLY; an audit event is
 * never updated or deleted. Read it back through {@link ActivityReadApi}.
 */
public interface ActivityWriteApi {

    /** Appends one event for the bound tenant; refuses (no SQL) when no tenant is bound. */
    void record(ActivityEntry entry);
}
