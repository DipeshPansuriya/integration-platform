package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.port.DeliveryCircuitFacts;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E10 T96 / T-183: the {@link DeliveryCircuitFacts} on the delivery tables - a {@code RETRY_WAIT} delivery whose LAST
 * {@code delivery_event} is {@code CIRCUIT_DEFER} was waiting for its target's open circuit; its {@code target_id} is
 * that target. Once the re-drive writes {@code REDRIVE} (or any later event) the delivery is no longer counted.
 * Read-only, in the caller's open, tenant-bound transaction.
 */
public final class JdbcDeliveryCircuitFacts extends TenantScopedRepository implements DeliveryCircuitFacts {

    public JdbcDeliveryCircuitFacts(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public Optional<UUID> circuitDeferredTarget(UUID deliveryId) {
        return query("select d.target_id from public.delivery d",
                "d.id = :id::uuid and d.state = 'RETRY_WAIT' and d.target_id is not null"
                        + " and (select e.type from public.delivery_event e where e.delivery_id = d.id"
                        + " and e.tenant_id = d.tenant_id order by e.id desc limit 1) = 'CIRCUIT_DEFER'",
                Map.of("id", deliveryId.toString()), (rs, n) -> UUID.fromString(rs.getString(1))).stream()
                .findFirst();
    }
}
