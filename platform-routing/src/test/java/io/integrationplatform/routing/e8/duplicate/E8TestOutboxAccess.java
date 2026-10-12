package io.integrationplatform.routing.e8.duplicate;

import io.integrationplatform.ingest.internal.relay.ClaimedOutboxRow;
import io.integrationplatform.ingest.internal.relay.OutboxRowAccess;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The claim/mark behind the relay in this suite: the very statements the real {@code JdbcOutboxAccess} runs
 * (T-008, V2_0_1's index) - claim one bucket's oldest unpublished rows {@code FOR UPDATE SKIP LOCKED} in
 * outbox-id order, mark {@code published_at} once. Written beside the suite because {@code JdbcOutboxAccess} is
 * package-private inside platform-ingest, and the lanes touch only their test trees: the chain this suite drives
 * is the real relay over the real PostgreSQL, and this access is the only piece a cross-module test has to
 * re-state (flagged to arjun-fullstack as a limit).
 *
 * <p>The {@code markAfterAck} flag is the crash window (the GHERKIN "the relay crashed after publishing and
 * before marking the row"): cycle 1 - the broker's acknowledge already landed, the process dies before the mark
 * commits, so {@link #markPublished(long)} never writes (the UPDATE behind it is what dies); cycle 2 re-runs the
 * relay and the mark lands. The row's state after cycle 1 - published on Kafka, unpublished in PostgreSQL - is
 * exactly the state a real crash between the two leaves.
 */
final class E8TestOutboxAccess extends TenantScopedRepository implements OutboxRowAccess {

    private static final String CLAIM_SELECT_FROM = """
            select id, topic, aggregate_id, record_key, traceparent, tenant_id \
            from (select id, topic, aggregate_id, record_key, traceparent, tenant_id \
                  from outbox \
                  where published_at is null and key_bucket = :key_bucket and available_at <= :available_at_or_before \
                  order by id \
                  limit :limit \
                  for update skip locked) claimed""";

    private static final String MARK_PUBLISH = "update outbox set published_at = now()";

    private static final Map<OutboxTopic, String> TOPIC_NAMES = topicNames();

    private volatile boolean markAfterAck;

    private static Map<OutboxTopic, String> topicNames() {
        Map<OutboxTopic, String> names = new EnumMap<>(OutboxTopic.class);
        for (OutboxTopic topic : OutboxTopic.values()) {
            names.put(topic, topic.topicName());
        }
        return Map.copyOf(names);
    }

    E8TestOutboxAccess(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** Opens (true) or closes (false) the crash window: whether a broker-acked publish is allowed to be marked. */
    void markAfterAck(boolean mark) {
        this.markAfterAck = mark;
    }

    @Override
    public List<ClaimedOutboxRow> claimUnpublished(int keyBucket, Instant availableAtOrBefore, int limit) {
        // the driver cannot infer the SQL type of an Instant - the explicit Timestamp is the cast of R09 timestamptz
        Timestamp instant = Timestamp.from(availableAtOrBefore);
        return query(CLAIM_SELECT_FROM, null,
                Map.of("key_bucket", keyBucket, "available_at_or_before", instant, "limit", limit),
                (rs, rowNum) -> new ClaimedOutboxRow(rs.getLong("id"), topicOf(rs.getString("topic")),
                        rs.getObject("aggregate_id", java.util.UUID.class), rs.getString("record_key"),
                        rs.getString("traceparent")));
    }

    @Override
    public boolean markPublished(long outboxId) {
        if (!markAfterAck) {
            // the crash window: the process is dead between the broker ack and this mark - nothing is written
            return false;
        }
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