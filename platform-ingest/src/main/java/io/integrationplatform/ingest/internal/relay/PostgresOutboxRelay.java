package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.OutboxRelay;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The outbox relay (T-008; architecture.md 4.1 step 6; ADR-005 Decisions 11 and 12). One {@link #runOnce()} cycle:
 * for every active tenant ({@link ActiveTenantSource}, ADR-011 Decision 8) and every key bucket, one
 * tenant-bound transaction claims the unpublished rows of that bucket ({@link OutboxRowAccess#claimUnpublished},
 * {@code FOR UPDATE SKIP LOCKED}) in outbox-id order, hands each as an {@link OutboundRecord} to the one narrow
 * {@link PublishPort}, waits for {@link PublishPort#acknowledge} with a timeout, and sets {@code published_at}
 * only for the acknowledged rows.
 *
 * <p>A crash at any point - broker slow, process killed, transaction lost - leaves every unmarked row for the
 * next cycle: at least once, never lost (RPO zero, E2-F1-S1, E2-C1). A row whose acknowledge failed stops this
 * bucket for the cycle - the relay never skips ahead of an unpublished row of the same bucket (ADR-005
 * Decision 11); the duplicate a crash can cause is detected by the record's idempotency key (T-010).
 *
 * <p>This class imports and calls only the port types - no vendor client (the ArchUnit rule in this test package
 * fails the build on one, T-041/E2-F1-S2).
 */
public final class PostgresOutboxRelay implements OutboxRelay {

    /** The key-bucket count the {@code outbox.key_bucket} CHECK fixes (0-63, ADR-005 Decision 11). */
    public static final int KEY_BUCKETS = 64;

    private final ActiveTenantSource tenantSource;
    private final OutboxRowAccess access;
    private final RelayTenantContext tenantContext;
    private final TransactionOperations transactions;
    private final PublishPort publishPort;
    private final Duration acknowledgeTimeout;
    private final int batchSize;

    public PostgresOutboxRelay(ActiveTenantSource tenantSource, OutboxRowAccess access,
            RelayTenantContext tenantContext, TransactionOperations transactions, PublishPort publishPort,
            Duration acknowledgeTimeout, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (acknowledgeTimeout.isZero() || acknowledgeTimeout.isNegative()) {
            throw new IllegalArgumentException("acknowledgeTimeout must be positive");
        }
        this.tenantSource = Objects.requireNonNull(tenantSource, "tenantSource");
        this.access = Objects.requireNonNull(access, "access");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.publishPort = Objects.requireNonNull(publishPort, "publishPort");
        this.acknowledgeTimeout = Objects.requireNonNull(acknowledgeTimeout, "acknowledgeTimeout");
        this.batchSize = batchSize;
    }

    /** T-191 red skeleton: the controlled constructor - controls not built yet. */
    public PostgresOutboxRelay(ActiveTenantSource tenantSource, OutboxRowAccess access,
            RelayTenantContext tenantContext, TransactionOperations transactions, PublishPort publishPort,
            Duration acknowledgeTimeout, int batchSize, RelaySettings settings,
            io.micrometer.core.instrument.MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations, java.time.Clock clock, RelayAlerts alerts) {
        this(tenantSource, access, tenantContext, transactions, publishPort, acknowledgeTimeout, batchSize);
    }

    @Override
    public int runOnce() {
        int marked = 0;
        for (UUID tenant : tenantSource.activeTenantIds()) {
            tenantContext.bind(tenant);
            try {
                for (int keyBucket = 0; keyBucket < KEY_BUCKETS; keyBucket++) {
                    final int bucket = keyBucket;
                    marked += transactions.execute(status -> publishBucket(bucket));
                }
            } finally {
                tenantContext.unbind();
            }
        }
        return marked;
    }

    private int publishBucket(int keyBucket) {
        // T-179 (ADR fix m-817272 B3): the bucket work is serialized before the claim, so a second relay
        // instance cannot SKIP LOCKED its way ahead of this transaction's unpublished rows and publish
        // them out of sequence (E8-C2; the container sibling proves the two-instance race).
        access.lockKeyBucket(keyBucket);
        List<ClaimedOutboxRow> rows = access.claimUnpublished(keyBucket, Instant.now(), batchSize);
        int marked = 0;
        for (ClaimedOutboxRow row : rows) {
            PublishReceipt receipt = publishPort.publish(new OutboundRecord(row.topic().topicName(),
                    row.recordKey(), row.outboxId(), row.aggregateId(), row.traceparent()));
            if (!publishPort.acknowledge(receipt, acknowledgeTimeout)) {
                break;
            }
            if (!access.markPublished(row.outboxId())) {
                break; // the row is no longer ours to mark (already published or gone): stop the bucket
            }
            marked++;
        }
        return marked;
    }
}
