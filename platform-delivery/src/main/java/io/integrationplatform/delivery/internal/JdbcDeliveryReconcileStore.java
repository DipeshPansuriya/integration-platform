package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T-180: the {@link DeliveryReconcileStore} contract on the delivery tables (RED stub). */
public final class JdbcDeliveryReconcileStore extends TenantScopedRepository implements DeliveryReconcileStore {

    public JdbcDeliveryReconcileStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public List<SendingClaim> sendingClaimedBefore(Instant claimedBefore, int limit) {
        return List.of();
    }

    @Override
    public List<UUID> lockReleasableHeld(int limit) {
        return List.of();
    }

    @Override
    public boolean release(UUID deliveryId) {
        return false;
    }
}
