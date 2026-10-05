package io.integrationplatform.ingest.internal.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.integrationplatform.ingest.internal.publish.InMemoryPublishPort;
import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import io.integrationplatform.tenancy.NoTenantBoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-008 (E2-F1-S1, E2-F1-S2): the outbox relay's cycle behavior, against fakes that honour the {@link OutboxRowAccess}
 * and {@link TransactionOperations} contracts. The real PostgreSQL claim (FOR UPDATE SKIP LOCKED, two relays at once)
 * is proven by the container sibling; the port-only call shape is proven here and by the ArchUnit sibling.
 */
class PostgresOutboxRelayTest {

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final int BATCH = 100;

    private static final UUID TENANT_A = tenant("tenant-a");
    private static final UUID TENANT_B = tenant("tenant-b");

    // ---------- scenario E2-F1-S2: "The relay uses the port only" ----------

    @Test
    void theRelayCallsOnlyPublishAndAcknowledgeOnThePort() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 7, 1, OutboxTopic.MESSAGE_ACCEPTED);
        PublishPort port = mock(PublishPort.class);
        PostgresOutboxRelay relay = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, BATCH);

        relay.runOnce();

        verify(port).publish(new OutboundRecord("platform.message.accepted", "key-1", 1, aggregate(1), null));
        verify(port).acknowledge(any(PublishReceipt.class), eq(ACK_TIMEOUT));
        verifyNoMoreInteractions(port);
    }

    // ---------- scenarios E2-F1-S1: crash loses nothing, sends nothing twice silently ----------

    @Test
    void aCyclePublishesAndMarksEveryUnpublishedRowOfEveryActiveTenant() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 3, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 40, 2, OutboxTopic.DELIVERY_DUE);
        access.add(TENANT_B, 63, 3, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort port = new InMemoryPublishPort();

        int marked = new PostgresOutboxRelay(() -> List.of(TENANT_A, TENANT_B), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, BATCH).runOnce();

        assertThat(marked).as("rows marked published").isEqualTo(3);
        assertThat(access.publishedCount()).isEqualTo(3);
        assertThat(port.published()).extracting(OutboundRecord::outboxId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void aCrashInsideTheMarkTransactionLeavesEveryRowUnmarkedForTheNextCycle() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 5, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 5, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 5, 3, OutboxTopic.MESSAGE_ACCEPTED);
        access.failMarkingAt(2); // simulates the process dying while the cycle transaction commits
        InMemoryPublishPort port = new InMemoryPublishPort();
        PostgresOutboxRelay relay = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, BATCH);

        assertThatThrownBy(relay::runOnce).isInstanceOf(IllegalStateException.class);

        // RPO zero: the crashed transaction applies nothing - not even the rows published before the crash.
        assertThat(access.publishedCount()).as("a crashed transaction marks nothing").isZero();
        assertThat(port.published()).as("rows 1 and 2 were already published to the broker").hasSize(2);

        port.reset();
        int marked = relay.runOnce();

        assertThat(marked).as("the next cycle publishes and marks all three (at least once)").isEqualTo(3);
        assertThat(access.publishedCount()).isEqualTo(3);
        assertThat(port.published()).extracting(OutboundRecord::idempotencyKey)
                .as("a re-published row carries the same idempotency key (T-010 detects it)")
                .startsWith("outbox:1", "outbox:2");
    }

    @Test
    void aRowThatWasNotAcknowledgedStaysUnpublishedAndTheBucketStopsAtIt() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 9, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 9, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 9, 3, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort port = new InMemoryPublishPort();
        port.refuseAcknowledgeOf(2);

        int marked = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, BATCH).runOnce();

        assertThat(marked).isEqualTo(1);
        assertThat(port.published()).extracting(OutboundRecord::outboxId)
                .as("row 3 is never published ahead of the unacknowledged row 2 (ADR-005 Decision 11)")
                .containsExactly(1L, 2L);
        assertThat(access.isPublished(2)).isFalse();
        assertThat(access.isPublished(3)).isFalse();
    }

    // ---------- scenario E2-F1-S1 @negative @tenant: the relay never mixes tenants ----------

    @Test
    void everyClaimRunsInsideTheTenantItsRowsBelongToAndNoRowIsReadOutsideItsTenant() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 1, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_B, 1, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 2, 3, OutboxTopic.DELIVERY_DUE);
        InMemoryPublishPort port = new InMemoryPublishPort();

        int marked = new PostgresOutboxRelay(() -> List.of(TENANT_A, TENANT_B), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, BATCH).runOnce();

        assertThat(marked).isEqualTo(3);
        assertThat(access.claimTenants()).as("every claim ran under a bound tenant of the tenant loop")
                .isNotEmpty()
                .allMatch(tenant -> tenant.equals(TENANT_A) || tenant.equals(TENANT_B));
        assertThat(port.published()).extracting(OutboundRecord::outboxId).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void nothingIsBoundOutsideTheTenantLoopAndAnUnboundClaimFailsClosed() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 1, 1, OutboxTopic.MESSAGE_ACCEPTED);

        new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context, new FakeTransactions(access),
                new InMemoryPublishPort(), ACK_TIMEOUT, BATCH).runOnce();

        assertThat(context.currentTenantId()).as("the thread is left unbound after the cycle").isEmpty();
        assertThatThrownBy(() -> access.claimUnpublished(0, Instant.now(), BATCH))
                .isInstanceOf(NoTenantBoundException.class);
    }

    // ---------- cycle shape ----------

    @Test
    void anEmptyTenantListMarksNothingAndATenantWithoutRowsIsSkipped() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 0, 1, OutboxTopic.MESSAGE_ACCEPTED);

        assertThat(new PostgresOutboxRelay(List::of, access, context, new FakeTransactions(access),
                new InMemoryPublishPort(), ACK_TIMEOUT, BATCH).runOnce()).isZero();
        assertThat(new PostgresOutboxRelay(() -> List.of(TENANT_B), access, context, new FakeTransactions(access),
                new InMemoryPublishPort(), ACK_TIMEOUT, BATCH).runOnce())
                .as("tenant B has no rows").isZero();
        assertThat(access.publishedCount()).isZero();
    }

    @Test
    void deferredRowsAreLeftForALaterCycleAndTheBatchLimitSplitsTheRest() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 2, 1, OutboxTopic.MESSAGE_ACCEPTED, Instant.now().plusSeconds(60));
        access.add(TENANT_A, 2, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 2, 3, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort port = new InMemoryPublishPort();
        PostgresOutboxRelay relay = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, 2);

        int firstCycle = relay.runOnce();

        assertThat(firstCycle).as("batch 2 takes rows 2 and 3; row 1 waits for its available_at")
                .isEqualTo(2);
        assertThat(access.isPublished(1)).as("the deferred row stays for a later cycle").isFalse();

        port.reset();
        int secondCycle = relay.runOnce();

        assertThat(secondCycle).as("rows 2 and 3 are marked now, so only the (not yet due) row 1 is left")
                .isZero();
    }

    // ---------- helpers ----------

    private static UUID tenant(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID aggregate(long outboxId) {
        return UUID.nameUUIDFromBytes(("agg-" + outboxId).getBytes(StandardCharsets.UTF_8));
    }

    /** Passes the callback through and applies or discards the staged marks - the real transaction boundary. */
    private static final class FakeTransactions implements TransactionOperations {

        private final FakeOutboxAccess access;

        FakeTransactions(FakeOutboxAccess access) {
            this.access = access;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            try {
                T result = action.doInTransaction(null);
                access.commitMarks();
                return result;
            } catch (RuntimeException failure) {
                access.rollbackMarks();
                throw failure;
            }
        }
    }

    /** An outbox table honouring the claim contract: bound tenant, bucket, availability, published flag. */
    private static final class FakeOutboxAccess implements OutboxRowAccess {

        private static final class Row {
            final UUID tenant;
            final int bucket;
            final long id;
            final OutboxTopic topic;
            final Instant availableAt;
            boolean published;

            Row(UUID tenant, int bucket, long id, OutboxTopic topic, Instant availableAt) {
                this.tenant = tenant;
                this.bucket = bucket;
                this.id = id;
                this.topic = topic;
                this.availableAt = availableAt;
            }
        }

        private final RelayTenantContext tenantContext;
        private final List<Row> rows = new ArrayList<>();
        private final List<Long> stagedMarks = new ArrayList<>();
        private final List<UUID> claimTenants = new ArrayList<>();
        private long crashAtMarkId;

        FakeOutboxAccess(RelayTenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        void add(UUID tenant, int bucket, long id, OutboxTopic topic) {
            add(tenant, bucket, id, topic, Instant.now().minusSeconds(1));
        }

        void add(UUID tenant, int bucket, long id, OutboxTopic topic, Instant availableAt) {
            rows.add(new Row(tenant, bucket, id, topic, availableAt));
        }

        void failMarkingAt(long id) {
            crashAtMarkId = id;
        }

        int publishedCount() {
            return (int) rows.stream().filter(row -> row.published).count();
        }

        boolean isPublished(long id) {
            return rows.stream().anyMatch(row -> row.id == id && row.published);
        }

        List<UUID> claimTenants() {
            return List.copyOf(claimTenants);
        }

        void commitMarks() {
            stagedMarks.forEach(id -> rows.stream().filter(row -> row.id == id)
                    .forEach(row -> row.published = true));
            stagedMarks.clear();
        }

        void rollbackMarks() {
            stagedMarks.clear();
        }

        @Override
        public List<ClaimedOutboxRow> claimUnpublished(int keyBucket, Instant availableAtOrBefore, int limit) {
            UUID tenant = tenantContext.requireTenantId();
            claimTenants.add(tenant);
            // The fake filters by the BOUND tenant exactly as the tenant predicate and row-level security do in
            // the real table (V2_0_1__outbox.sql); the container test proves the database enforces it.
            return rows.stream()
                    .filter(row -> row.tenant.equals(tenant))
                    .filter(row -> !row.published)
                    .filter(row -> row.bucket == keyBucket)
                    .filter(row -> !row.availableAt.isAfter(availableAtOrBefore))
                    .sorted((left, right) -> Long.compare(left.id, right.id))
                    .limit(limit)
                    .map(row -> new ClaimedOutboxRow(row.id, row.topic, aggregate(row.id), "key-" + row.id, null))
                    .toList();
        }

        @Override
        public boolean markPublished(long outboxId) {
            if (outboxId == crashAtMarkId) {
                throw new IllegalStateException("crash while marking row " + outboxId);
            }
            stagedMarks.add(outboxId);
            return true;
        }
    }
}
