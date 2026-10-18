package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-180: the {@link DeliveryReconcileStore} contract on the delivery tables. Every method runs in the caller's
 * open, tenant-bound transaction and reads or changes the bound tenant's rows only; a release that matches
 * nothing is {@code false}, never an exception.
 */
public final class JdbcDeliveryReconcileStore extends TenantScopedRepository implements DeliveryReconcileStore {

    private final JdbcDeliveryRepository repository;

    public JdbcDeliveryReconcileStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
    }

    @Override
    public List<SendingClaim> sendingClaimedBefore(Instant claimedBefore, int limit) {
        return query(
                "select scoped.id, scoped.claim_token, scoped.claimed_at, scoped.target_id, scoped.attempts"
                        + " from (select d.id, d.claim_token, d.claimed_at, d.target_id,"
                        + " (select count(*) from public.delivery_attempt a"
                        + " where a.delivery_id = d.id and a.tenant_id = :tenant_id::uuid"
                        // the current series - rows started after the latest REPLAY (as lockDue counts it)
                        + " and a.started_at > coalesce((select max(e.occurred_at) from public.delivery_event e"
                        + " where e.delivery_id = d.id and e.tenant_id = d.tenant_id and e.type = 'REPLAY'),"
                        + " '-infinity'::timestamptz)) as attempts,"
                        + " d.tenant_id from public.delivery d"
                        + " where d.tenant_id = :tenant_id::uuid and d.state = 'SENDING'"
                        + " and d.claimed_at <= :cutoff order by d.claimed_at limit :limit) scoped",
                null,
                Map.of("cutoff", OffsetDateTime.ofInstant(claimedBefore, ZoneOffset.UTC), "limit", limit),
                (rs, n) -> new SendingClaim(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getTimestamp(3).toInstant(), rs.getString(4) == null ? null
                                : UUID.fromString(rs.getString(4)), rs.getInt(5)));
    }

    @Override
    public List<UUID> lockReleasableHeld(int limit) {
        // ADR-011 D6's hold evaluated tenant-wide: HELD with no earlier non-final delivery of its scope
        return query("select held.id from ("
                        + " select h.id, h.tenant_id, h.key_seq from public.delivery h"
                        + " where h.tenant_id = :tenant_id::uuid and h.state = 'HELD'"
                        + " and h.interface_id is not null and h.business_key is not null"
                        + " and h.target_id is not null and h.key_seq is not null and not exists ("
                        + "   select 1 from public.delivery b"
                        + "   where b.tenant_id = h.tenant_id and b.interface_id = h.interface_id"
                        + "   and b.business_key = h.business_key and b.target_id = h.target_id"
                        + "   and b.key_seq < h.key_seq and b.state not in ('DELIVERED', 'DEAD_LETTERED'))"
                        + " order by h.key_seq, h.id limit :limit for update of h skip locked) held",
                null,
                Map.of("limit", limit),
                (rs, n) -> UUID.fromString(rs.getString(1)));
    }

    @Override
    public boolean release(UUID deliveryId) {
        return repository.release(deliveryId, Instant.now());
    }
}
