package io.integrationplatform.delivery.internal.replay;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.replay.OperatorActionStore;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T111: the operator-action store on the real {@code public.delivery} table. The read locks the row
 * {@code FOR UPDATE} in the caller's transaction (the {@code readForClaim} shape: the inner select carries the
 * tenant predicate and the lock, the base adds its predicate on the outer read). The two moves reuse the existing
 * conditional transitions of {@link JdbcDeliveryRepository} - {@code replay} (DEAD_LETTERED to DUE, event REPLAY)
 * and {@code redrive} (RETRY_WAIT to DUE, event REDRIVE) - so the state machine and the {@code delivery_event}
 * ledger stay in one place.
 */
final class JdbcOperatorActionStore extends TenantScopedRepository implements OperatorActionStore {

    private final JdbcDeliveryRepository deliveries;

    JdbcOperatorActionStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.deliveries = new JdbcDeliveryRepository(jdbcClient, tenantContext);
    }

    @Override
    public Optional<Target> lockForAction(UUID deliveryId) {
        return query("select scoped.id, scoped.state, scoped.mapping_version_id, scoped.contract_version_id,"
                        + " scoped.target_id from ("
                        + " select d.id, d.state, d.mapping_version_id, d.contract_version_id, d.target_id, d.tenant_id"
                        + " from public.delivery d"
                        + " where d.tenant_id = :tenant_id::uuid and d.id = :id::uuid for update) scoped",
                null, Map.of("id", deliveryId.toString()),
                (rs, n) -> new Target(uuid(rs, 1), DeliveryState.valueOf(rs.getString(2)), uuid(rs, 3), uuid(rs, 4),
                        uuid(rs, 5)))
                .stream().findFirst();
    }

    @Override
    public boolean pinContract(UUID deliveryId, UUID contractVersionId) {
        // the row is already locked FOR UPDATE by lockForAction in this transaction; the NULL guard keeps a pin immutable
        return update("update public.delivery set contract_version_id = :contract_version_id::uuid",
                "id = :id::uuid and contract_version_id is null",
                Map.of("id", deliveryId.toString(), "contract_version_id", contractVersionId.toString())) >= 1;
    }

    @Override
    public boolean replay(UUID deliveryId, Instant at) {
        return deliveries.replay(deliveryId, at);
    }

    @Override
    public boolean retryNow(UUID deliveryId, Instant at) {
        return deliveries.redrive(deliveryId, at);
    }

    private static UUID uuid(ResultSet rs, int column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }
}
