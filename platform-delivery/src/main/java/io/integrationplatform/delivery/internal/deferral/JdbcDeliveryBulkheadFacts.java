package io.integrationplatform.delivery.internal.deferral;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.ordering.OrderingKey;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@link DeliveryBulkheadFacts} port on the real tables (T-177 facts, read before T-198's deferral): the pinned
 * target, the ordering key derived exactly as the final-state release derives it ({@link OrderingKey#recordKeyFor},
 * {@code null} for ordering {@code NONE}) and the current {@code bulkhead_deferrals}. A plain read - no row lock;
 * the claim and the conditional deferral stay the only writers. A row the tenant cannot see, or one already final,
 * answers empty. Opens its own transaction (or joins the caller's) and stamps the tenant (R11).
 */
public final class JdbcDeliveryBulkheadFacts extends TenantScopedRepository implements DeliveryBulkheadFacts {

    private final TenantContext tenantContext;
    private final JdbcDeliveryRepository repository;
    private final TransactionOperations transactions;

    public JdbcDeliveryBulkheadFacts(JdbcClient jdbcClient, TenantContext tenantContext,
            TransactionOperations transactions) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Optional<BulkheadFacts> forDelivery(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        UUID tenantId = tenantContext.requireTenantId();
        return transactions.execute(status -> {
            repository.stampTenant();
            return query("select d.target_id, d.interface_id, d.business_key, d.bulkhead_deferrals"
                            + " from public.delivery d",
                    "d.id = :id::uuid and d.state not in ('DELIVERED', 'DEAD_LETTERED')",
                    Map.of("id", deliveryId.toString()),
                    (rs, n) -> {
                        String interfaceId = rs.getString(2);
                        String businessKey = rs.getString(3);
                        String orderingKey = interfaceId != null && businessKey != null
                                ? OrderingKey.recordKeyFor(tenantId, UUID.fromString(interfaceId), businessKey)
                                : null;
                        String targetId = rs.getString(1);
                        return new BulkheadFacts(targetId == null ? null : UUID.fromString(targetId),
                                orderingKey, rs.getInt(4));
                    }).stream().findFirst();
        });
    }
}
