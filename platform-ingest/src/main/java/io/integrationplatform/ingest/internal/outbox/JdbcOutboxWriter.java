package io.integrationplatform.ingest.internal.outbox;

import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-007 (E2-F1): the transactional-outbox writer (ADR-001, ADR-005, architecture.md 4.1 step 5).
 *
 * <p>Runs its single INSERT through {@link TenantScopedRepository} - the one SQL path the ArchUnit rule of
 * {@code arch-test} permits (R08) - and the base class refuses the statement before any call goes out when no
 * tenant is bound ({@link io.integrationplatform.tenancy.NoTenantBoundException}, fails closed) or when no
 * transaction is active (R11). It opens no transaction of its own: the SQL runs on the connection bound to the
 * caller's transaction, so the row commits or rolls back together with the caller's business rows (E2-F1-S1
 * "kill the caller after the append").
 *
 * <p>The tenant column is written from the bound {@link TenantContext} with the explicit {@code ::uuid} cast (R09);
 * the RLS {@code WITH CHECK} of {@code V2_0_1__outbox.sql} is the second guard. {@code key_bucket} is the
 * deterministic ADR-005 Decision 11 derivation from the record key ({@link KeyBuckets}); {@code published_at} is
 * left unset so it stays {@code NULL} until the relay (T-008) marks it.
 *
 * <p>The {@code with ins as (insert ... returning id, tenant_id) select id from ins} shape is what lets an INSERT
 * keep the mandatory tenant predicate of the base class: the {@code WHERE} the base appends filters the RETURNING
 * row, so an id only comes back for a row that really carries the bound tenant - an append that did not is a bug
 * and throws, it never returns a foreign row's id.
 */
public class JdbcOutboxWriter extends TenantScopedRepository implements OutboxWriter {

    private static final String APPEND_DUE_NOW = """
            with ins as (
                insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket, traceparent)
                values (:tenant_id::uuid, :topic, :aggregate_id::uuid, :record_key, :key_bucket, :traceparent)
                returning id, tenant_id)
            select id from ins""";

    private static final String APPEND_DEFERRED = """
            with ins as (
                insert into public.outbox
                    (tenant_id, topic, aggregate_id, record_key, key_bucket, traceparent, available_at)
                values (:tenant_id::uuid, :topic, :aggregate_id::uuid, :record_key, :key_bucket, :traceparent,
                        :available_at)
                returning id, tenant_id)
            select id from ins""";

    public JdbcOutboxWriter(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public long append(OutboxEntry entry) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topic", entry.topic().topicName());
        params.put("aggregate_id", entry.aggregateId().toString());
        params.put("record_key", entry.recordKey());
        params.put("key_bucket", KeyBuckets.of(entry.recordKey()));
        params.put("traceparent", entry.traceparent());
        String sql = APPEND_DUE_NOW;
        if (entry.availableAt() != null) {
            sql = APPEND_DEFERRED;
            params.put("available_at", Timestamp.from(entry.availableAt()));
        }
        // The base adds: WHERE tenant_id = :tenant_id::uuid - it stamps AND filters (R08), and requireTenantId()
        // and the active-transaction check run inside it before the statement is created.
        List<Long> ids = query(sql, null, params, (rs, rowNum) -> rs.getLong("id"));
        if (ids.size() != 1) {
            throw new IllegalStateException("outbox append stored no row for the bound tenant - the stamped tenant"
                    + " and the written tenant_id disagreed (never expected; RLS WITH CHECK also guards this)");
        }
        return ids.get(0);
    }
}
