package io.integrationplatform.ingest.port;

/**
 * Appends one row to the outbox inside the caller's transaction - the transactional outbox (ADR-001, ADR-005).
 *
 * <p>Contract: the row is written in the caller's open, tenant-bound transaction and never in a transaction of its
 * own, so it commits or rolls back together with the caller's business rows; it is stamped with the tenant bound in
 * {@code TenantContext} and fails closed ({@code NoTenantBoundException}) when none is bound.
 *
 * <p>Implemented by lane A (T-007); called by the intake transaction and by lane B's re-drive (T-090).
 */
public interface OutboxWriter {

    /**
     * Writes the row and returns its outbox id (ascending in insert order).
     *
     * @throws io.integrationplatform.tenancy.NoTenantBoundException when no tenant is bound
     */
    long append(OutboxEntry entry);
}
