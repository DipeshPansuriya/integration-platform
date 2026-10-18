package io.integrationplatform.app.composition;

import io.integrationplatform.tenancy.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * The application's one TenantContext once background work runs (T-049 composition; R08/R11): it answers the tenant
 * bound on the current thread by whichever binding source owns the thread - the request's identity (console user or
 * source credential) on a request thread, the outbox relay's per-cycle binding on the relay thread, the stage worker's
 * binding on a consumer thread. Every repository's tenant predicate and the transaction's {@code app.tenant_id} stamp
 * read it, so one stamping transaction manager serves requests and workers alike (the composite the E2 chaos suite
 * proved, now in production wiring).
 *
 * <p>Fail closed: no binding -> empty (every tenant-scoped call then throws before any SQL); two sources bound to
 * DIFFERENT tenants on one thread -> an exception, never a choice between them.
 */
final class WorkerAwareTenantContext implements TenantContext, SmartInitializingSingleton {

    private final ObjectProvider<TenantContext> contexts;
    private volatile List<TenantContext> sources;

    WorkerAwareTenantContext(ObjectProvider<TenantContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public void afterSingletonsInstantiated() {
        sources = resolveSources();
    }

    @Override
    public Optional<UUID> currentTenantId() {
        UUID found = null;
        for (TenantContext source : sources()) {
            Optional<UUID> bound = source.currentTenantId();
            if (bound.isEmpty()) {
                continue;
            }
            if (found != null && !found.equals(bound.get())) {
                throw new IllegalStateException("two different tenants are bound on this thread - refusing to choose");
            }
            found = bound.get();
        }
        return Optional.ofNullable(found);
    }

    private List<TenantContext> sources() {
        List<TenantContext> known = sources;
        return known != null ? known : resolveSources(); // before the context finished starting: look up each time
    }

    private List<TenantContext> resolveSources() {
        return contexts.orderedStream().filter(context -> context != this).toList();
    }
}
