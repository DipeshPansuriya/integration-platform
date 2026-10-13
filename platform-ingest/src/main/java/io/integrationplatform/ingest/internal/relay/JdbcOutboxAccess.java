package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.ingest.port.OutboxTopic;
import java.sql.Timestamp;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real claim and mark behind {@link OutboxRowAccess} (T-008) - every statement runs through
 * {@link TenantScopedRepository}, so each carries the tenant predicate (R08) inside the tenant-bound transaction
 * (R11), and the sub-query form keeps the {@code FOR UPDATE SKIP LOCKED} claim in outbox-id order exactly as
 * {@code V2_0_1__outbox.sql} indexes it: {@code (tenant_id, key_bucket, id) WHERE published_at IS NULL}.
 */
public final class JdbcOutboxAccess extends TenantScopedRepository implements OutboxRowAccess {

    /** The claim: one bucket's oldest unpublished due rows, locked for this transaction only. */
    private static final String CLAIM_SELECT_FROM = """
            select id, topic, aggregate_id, record_key, traceparent, tenant_id \
            from (select id, topic, aggregate_id, record_key, traceparent, tenant_id \
                  from outbox \
                  where published_at is null and key_bucket = :key_bucket and available_at <= :available_at_or_before \
                  order by id \
                  limit :limit \
                  for update skip locked) claimed""";

    private static final String MARK_PUBLISH = "update outbox set published_at = now()";

    /**
     * T-179 (ADR fix m-817272 B3), styled after the delivery claim's ordering-key lock
     * ({@code JdbcDeliveryRepository.lockOrderingKey}): a transaction-level advisory lock hashed from the bound
     * tenant and the bucket number - the two values {@code outbox_unpublished_idx} names the rows by - so one
     * key bucket's rows are published by one relay transaction at a time, held to commit.
     */
    private static final String LOCK_KEY_BUCKET =
            "select pg_advisory_xact_lock(hashtextextended(:scope, 0))"
                    + " from (values (:own_tenant::uuid)) as scoped (tenant_id)";

    private static final Map<OutboxTopic, String> TOPIC_NAMES = topicNames();

    private static Map<OutboxTopic, String> topicNames() {
        Map<OutboxTopic, String> names = new EnumMap<>(OutboxTopic.class);
        for (OutboxTopic topic : OutboxTopic.values()) {
            names.put(topic, topic.topicName());
        }
        return Map.copyOf(names);
    }

    private final TenantContext scopedTenantContext;

    public JdbcOutboxAccess(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.scopedTenantContext = tenantContext;
    }

    @Override
    public void lockKeyBucket(int keyBucket) {
        // the (tenant, bucket) identity goes into the advisory hash as the bound tenant's uuid text plus the
        // bucket number: tenants never serialize against each other, and one key never spans two tenants
        // (the claim's tenant predicate, R08).
        UUID tenant = scopedTenantContext.requireTenantId();
        query(LOCK_KEY_BUCKET, null,
                Map.of("scope", tenant + ":" + keyBucket, "own_tenant", tenant),
                (rs, rowNum) -> Boolean.TRUE).forEach(unused -> { });
    }

    @Override
    public List<ClaimedOutboxRow> claimUnpublished(int keyBucket, Instant availableAtOrBefore, int limit) {
        // the driver cannot infer the SQL type of an Instant - the explicit Timestamp is the cast of R09 for timestamptz
        Timestamp instant = Timestamp.from(availableAtOrBefore);
        return query(CLAIM_SELECT_FROM, null,
                Map.of("key_bucket", keyBucket, "available_at_or_before", instant, "limit", limit),
                (rs, rowNum) -> new ClaimedOutboxRow(rs.getLong("id"), topicOf(rs.getString("topic")),
                        rs.getObject("aggregate_id", java.util.UUID.class), rs.getString("record_key"),
                        rs.getString("traceparent")));
    }

    @Override
    public boolean markPublished(long outboxId) {
        // "and published_at is null": a mark never rewrites an existing one (append-only semantics of the outbox).
        return update(MARK_PUBLISH, "id = :outbox_id and published_at is null", Map.of("outbox_id", outboxId)) == 1;
    }

    private static OutboxTopic topicOf(String stored) {
        for (Map.Entry<OutboxTopic, String> entry : TOPIC_NAMES.entrySet()) {
            if (entry.getValue().equals(stored)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("outbox.topic value not in the register: " + stored);
    }
}
