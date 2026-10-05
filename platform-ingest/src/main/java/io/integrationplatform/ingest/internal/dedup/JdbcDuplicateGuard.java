package io.integrationplatform.ingest.internal.dedup;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-010: the {@link DuplicateGuard} on the {@code V2_1_1__idempotency_key.sql} storage.
 *
 * <p>One statement, one round-trip: the claim is an {@code INSERT ... ON CONFLICT DO NOTHING RETURNING} wrapped in
 * the data-modifying CTE that lets an INSERT keep {@link TenantScopedRepository}'s mandatory tenant predicate (the
 * appended {@code WHERE} filters the RETURNING row - the id only comes back for a claim this tenant really made
 * here and now). A returned row means the claim is new: the record passed. An empty result means the unique index
 * refused it: a committed claim already exists, so the record is a duplicate.
 *
 * <p>The statement runs on the connection bound to the caller's transaction (never one of its own), so a claim is
 * atomic with the consumer's work: if the consumer rolls back, the claim is gone and the redelivery is handled.
 * Fail-closed rules (no tenant, no transaction) are the frozen base's (R08, R11); the per-tenant unique key means
 * two tenants' identical keys never collide - no cross-tenant signal leaks either way.
 */
public class JdbcDuplicateGuard extends TenantScopedRepository implements DuplicateGuard {

    private static final String CLAIM_SQL = """
            with ins as (
                insert into public.idempotency_key (tenant_id, idempotency_key)
                values (:tenant_id::uuid, :idempotency_key)
                on conflict (tenant_id, idempotency_key) do nothing
                returning id, tenant_id)
            select id from ins""";

    public JdbcDuplicateGuard(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public boolean isDuplicate(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // A blank key could only ever claim-everything or claim-nothing; either is a caller bug, refused
            // before the database is touched (the storage CHECK is the second guard, R10).
            throw new IllegalArgumentException("a blank idempotency key is never a claim: pass"
                    + " OutboundRecord.idempotencyKey()");
        }
        List<Long> claimed = query(CLAIM_SQL, null, Map.of("idempotency_key", idempotencyKey),
                (rs, rowNum) -> rs.getLong("id"));
        return claimed.isEmpty();
    }
}
