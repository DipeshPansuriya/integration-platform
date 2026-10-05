package io.integrationplatform.ingest.chaos;

import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lane D chaos binding: the test's own {@link TenantContext} ("tests may supply their own implementation" - the E1
 * seam's wording). The chaos suite binds the tenant the same way lane B's principal resolver will: the bound value
 * is read by tenancy's {@code TenantStampingTransactionManager} at every transaction start, so {@code app.tenant_id}
 * is stamped from here and nowhere else (R08, ADR-007 Decision 6) - exactly the path lane A's
 * {@code OutboxWriter} contract requires ("the tenant bound in TenantContext").
 */
final class ChaosTenantContext implements TenantContext {

    private final AtomicReference<UUID> bound = new AtomicReference<>();

    @Override
    public Optional<UUID> currentTenantId() {
        return Optional.ofNullable(bound.get());
    }

    void bind(UUID tenantId) {
        bound.set(java.util.Objects.requireNonNull(tenantId, "tenantId"));
    }

    void clear() {
        bound.set(null);
    }
}
