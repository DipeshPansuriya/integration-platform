package io.integrationplatform.delivery.internal.deferral;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.ordering.OrderingKey;
import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.OrderedStateChange;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@link BulkheadDeferrals} port on the real tables (T-198, E9-F1-S4, ADR-010 section 2): the delivery's
 * ordering scope is read first (its fields never change after the stage-1 insert), then ONE state change runs
 * under the one lock order of {@link OrderedStateChange} (key lock, message row, delivery row): the conditional
 * {@code PENDING/DUE -> RETRY_WAIT} update with {@code next_attempt_at} from {@link BulkheadBackoff} and the
 * {@code bulkhead_deferrals} increment, plus its {@code BULKHEAD_FULL} event. No attempt row - a deferral is not a
 * call. 0 matched rows (claimed or moved by another transaction) is a {@code false}: no effect, no event.
 *
 * <p>The dispatch calls this under the record's bound tenant with no transaction open: the scope read opens its
 * own (or joins the caller's) and stamps the tenant (R11); the state change runs in the helper's transaction.
 */
public final class JdbcBulkheadDeferrals extends TenantScopedRepository implements BulkheadDeferrals {

    private final TenantContext tenantContext;
    private final JdbcDeliveryRepository repository;
    private final TransactionOperations transactions;
    private final OrderedStateChange ordered;

    public JdbcBulkheadDeferrals(JdbcClient jdbcClient, TenantContext tenantContext,
            TransactionOperations transactions, OrderedStateChange ordered) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ordered = Objects.requireNonNull(ordered, "ordered");
    }

    @Override
    public boolean defer(UUID deliveryId, Instant now, int consecutiveDeferrals, double jitter) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(now, "now");
        Instant nextAttemptAt = now.plus(BulkheadBackoff.waitDuration(consecutiveDeferrals, jitter));
        JdbcDeliveryRepository.Scope scope = transactions.execute(status -> {
            repository.stampTenant();
            return repository.readScope(deliveryId);
        });
        if (scope == null) {
            return false; // not visible to the bound tenant: acknowledged, no effect
        }
        String orderingKey = scope.interfaceId() != null && scope.businessKey() != null
                ? OrderingKey.recordKeyFor(tenantContext.requireTenantId(), scope.interfaceId(), scope.businessKey())
                : null; // ordering NONE: the key lock is skipped
        return Boolean.TRUE.equals(ordered.run(orderingKey, scope.messageId(), List.of(deliveryId),
                () -> deferIfClaimable(deliveryId, nextAttemptAt, now)));
    }

    private boolean deferIfClaimable(UUID deliveryId, Instant nextAttemptAt, Instant now) {
        int rows = update(
                "update public.delivery set state = 'RETRY_WAIT', updated_at = :at,"
                        + " next_attempt_at = :next_attempt_at, bulkhead_deferrals = bulkhead_deferrals + 1",
                "id = :id::uuid and state in ('PENDING', 'DUE')",
                Map.of("at", utc(now), "next_attempt_at", utc(nextAttemptAt), "id", deliveryId.toString()));
        if (rows == 0) {
            return false;
        }
        repository.appendEvent(deliveryId, DeliveryEventType.BULKHEAD_FULL, "retry at " + nextAttemptAt, now);
        return true;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
