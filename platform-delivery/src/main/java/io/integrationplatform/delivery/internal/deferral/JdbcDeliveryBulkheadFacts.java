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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@link DeliveryBulkheadFacts} port on the real tables (T-177 facts, read before T-198's deferral): the pinned
 * target, the ordering key derived exactly as the final-state release derives it ({@link OrderingKey#recordKeyFor},
 * {@code null} for ordering {@code NONE}) and the current {@code bulkhead_deferrals}. A plain read - no row lock;
 * the claim and the conditional deferral stay the only writers. A row the tenant cannot see, or one already final,
 * answers empty. Opens its own transaction (or joins the caller's) and stamps the tenant (R11).
 *
 * <p>A delivery with no pinned target ({@code target_id} is nullable, V2_3_1) has no bulkhead to take a permit
 * from: it answers empty with a logged reason, so the dispatch acknowledges it with no claim (B19 F4) - never an
 * exception, which the stage base would retry forever and stall the partition with.
 */
public final class JdbcDeliveryBulkheadFacts extends TenantScopedRepository implements DeliveryBulkheadFacts {

    private static final Logger log = LoggerFactory.getLogger(JdbcDeliveryBulkheadFacts.class);

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
                        String targetId = rs.getString(1);
                        if (targetId == null) {
                            log.warn("delivery {} has no pinned target (target_id is null): no bulkhead, no claim -"
                                    + " acknowledged with no effect", deliveryId);
                            return Optional.<BulkheadFacts>empty();
                        }
                        String interfaceId = rs.getString(2);
                        String businessKey = rs.getString(3);
                        String orderingKey = interfaceId != null && businessKey != null
                                ? OrderingKey.recordKeyFor(tenantId, UUID.fromString(interfaceId), businessKey)
                                : null;
                        return Optional.of(new BulkheadFacts(UUID.fromString(targetId), orderingKey, rs.getInt(4)));
                    }).stream().findFirst().flatMap(facts -> facts);
        });
    }
}
