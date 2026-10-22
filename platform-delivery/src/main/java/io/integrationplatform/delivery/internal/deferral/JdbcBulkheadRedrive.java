package io.integrationplatform.delivery.internal.deferral;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.BulkheadRedrive;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@link BulkheadRedrive} port on the real tables (ADR-010 section 2 addendum): in ONE transaction under the
 * bound tenant, lock the oldest bulkhead-deferred {@code RETRY_WAIT} delivery of the target
 * ({@code FOR UPDATE SKIP LOCKED}, so a concurrent retry-job run or a second freed slot never takes the same row),
 * move it {@code RETRY_WAIT -> DUE} through the same conditional update the retry job uses, and append its
 * {@code DELIVERY_DUE} outbox row - the state change and its due record commit together, exactly as the job's
 * re-drive does. The backoff time is ignored on purpose: the wait was for this slot.
 *
 * <p>Ordering is untouched: a delivery behind an unfinished predecessor is HELD by the claim as before. A delivery of
 * another tenant is invisible (the tenant predicate and RLS). One call moves at most one row.
 */
public final class JdbcBulkheadRedrive extends TenantScopedRepository implements BulkheadRedrive {

    private final JdbcDeliveryRepository repository;
    private final TransactionOperations transactions;
    private final OutboxWriter outbox;

    public JdbcBulkheadRedrive(JdbcClient jdbcClient, TenantContext tenantContext,
            TransactionOperations transactions, OutboxWriter outbox) {
        super(jdbcClient, tenantContext);
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public boolean redriveOneDeferred(UUID targetEndpointId) {
        Objects.requireNonNull(targetEndpointId, "targetEndpointId");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            repository.stampTenant();
            UUID next = query(
                    "select scoped.id from (select d.id, d.tenant_id from public.delivery d"
                            + " where d.tenant_id = :tenant_id::uuid and d.state = 'RETRY_WAIT'"
                            + " and d.bulkhead_deferrals > 0 and d.target_id = :target::uuid"
                            + " order by d.next_attempt_at, d.id limit 1 for update of d skip locked) scoped",
                    null, Map.of("target", targetEndpointId.toString()),
                    (rs, n) -> UUID.fromString(rs.getString(1))).stream().findFirst().orElse(null);
            if (next == null || !repository.redrive(next, Instant.now())) {
                return false; // nothing waiting, or it moved meanwhile: no effect
            }
            outbox.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, next, "delivery:" + next, null, null));
            return true;
        }));
    }
}
