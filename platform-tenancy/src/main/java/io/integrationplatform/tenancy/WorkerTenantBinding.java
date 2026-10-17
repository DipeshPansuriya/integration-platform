package io.integrationplatform.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant of one background unit of work (T-049 composition; ADR-011 Decision 8): a stage consumer resolves the
 * tenant of the row a record names through the reviewed {@code ipaas_work_tenant} crossing - never from the record -
 * and binds it here for exactly the duration of that row's work, so every tenant-scoped read and write and every
 * transaction stamp on the thread answers for that tenant and no other. Thread-confined; a request thread never binds
 * here (its tenant is the request's identity).
 *
 * <p>Fail-closed rules: a binding always ends with its unit of work (try/finally), and a nested binding may repeat
 * the tenant already bound but never switch to another one on the same thread.
 */
public final class WorkerTenantBinding implements TenantContext {

    private final ThreadLocal<UUID> bound = new ThreadLocal<>();

    /** Runs {@code work} with {@code tenantId} bound on this thread and returns its result. */
    public <T> T callAs(UUID tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        UUID outer = bound.get();
        if (outer != null && !outer.equals(tenantId)) {
            throw new IllegalStateException("this worker thread already works for another tenant - a nested binding"
                    + " never switches tenants");
        }
        bound.set(tenantId);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                bound.remove();
            }
        }
    }

    /** Runs {@code work} with {@code tenantId} bound on this thread. */
    public void runAs(UUID tenantId, Runnable work) {
        Objects.requireNonNull(work, "work");
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    @Override
    public Optional<UUID> currentTenantId() {
        return Optional.ofNullable(bound.get());
    }
}
