package io.integrationplatform.tenancy;

/** A tenant-scoped access was attempted with no tenant bound; the access fails closed (ADR-009 Decision 5). */
public class NoTenantBoundException extends IllegalStateException {

    public NoTenantBoundException() {
        super("No tenant is bound to the current unit of work; tenant-scoped access is refused");
    }
}
