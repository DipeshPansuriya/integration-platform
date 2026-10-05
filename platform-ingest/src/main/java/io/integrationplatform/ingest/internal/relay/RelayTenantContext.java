package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant bound to the relay's current cycle step (T-008; ADR-011 Decision 8: a scanning job binds one tenant
 * per transaction). Thread-confined, deliberately not the request context: a background job has no security
 * context, and nothing may be bound outside the loop - the cycle unbinds before the next tenant starts and the
 * thread is returned clean (R11: the tenant setting is stamped per transaction, and only a bound tenant stamps).
 *
 * <p>Composition (platform-app, arjun-fullstack) passes this instance both to the relay and to the stamping
 * transaction manager so the bind is what the transaction stamps.
 */
public final class RelayTenantContext implements TenantContext {

    private final ThreadLocal<UUID> bound = new ThreadLocal<>();

    void bind(UUID tenantId) {
        bound.set(Objects.requireNonNull(tenantId, "tenantId"));
    }

    void unbind() {
        bound.remove();
    }

    @Override
    public Optional<UUID> currentTenantId() {
        return Optional.ofNullable(bound.get());
    }
}
