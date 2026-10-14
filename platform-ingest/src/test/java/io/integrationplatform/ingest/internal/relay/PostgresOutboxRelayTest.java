package io.integrationplatform.ingest.internal.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.ingest.internal.publish.InMemoryPublishPort;
import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import io.integrationplatform.tenancy.NoTenantBoundException;
import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
        when(port.publish(any(OutboundRecord.class))).thenAnswer(invocation -> {
            OutboundRecord record = invocation.getArgument(0);
            return (PublishReceipt) () -> record.outboxId();
        });
        when(port.acknowledge(any(PublishReceipt.class), eq(ACK_TIMEOUT))).thenReturn(true);
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

        access.clearCrash(); // the "process" restarts clean - the next cycle is an ordinary one
        port.reset();
        // T-191: a restarted process is a NEW relay - it has no memory of the broker's acknowledgements, so it
        // re-sends rows 1 and 2 (the same relay instance would only mark them, never re-send - see the T-191 test).
        PostgresOutboxRelay restarted = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context,
                new FakeTransactions(access), port, ACK_TIMEOUT, BATCH);
        int marked = restarted.runOnce();

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

    // ---------- T-179 (E8-F1-S1 @negative, ADR fix m-817272 B3): one relay publish per key bucket at a time ----------

    @Test
    void theRelayTakesTheKeyBucketAdvisoryLockBeforeItClaimsThatBucket() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 7, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 7, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 9, 3, OutboxTopic.DELIVERY_DUE);
        PostgresOutboxRelay relay = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context,
                new FakeTransactions(access), new InMemoryPublishPort(), ACK_TIMEOUT, BATCH);

        relay.runOnce();

        assertThat(access.operationLog())
                .as("the bucket lock is taken inside the bucket's transaction, before any row of it is claimed -"
                        + " a second relay instance waits instead of SKIP LOCKED-ing rows ahead of an"
                        + " unpublished row (E8-C2 same-key order)")
                .containsSubsequence("lock:7", "claim:7", "lock:9", "claim:9");
    }

    @Test
    void aKeyBucketLockIsTakenForEveryBucketTheCycleWorks() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 0, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 63, 2, OutboxTopic.MESSAGE_ACCEPTED);

        new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context, new FakeTransactions(access),
                new InMemoryPublishPort(), ACK_TIMEOUT, BATCH).runOnce();

        assertThat(access.lockedBuckets()).as("every bucket the cycle works is locked before its claim,")
                .contains("lock:0", "lock:63");
    }

    // ---------- T-191 (E8-F1-S3; ADR-005 Decision 12): the five controls on the relay ----------

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01";

    /** Deterministic settings: breaker after N failures, open 30 s; backoff 1 s doubling, no jitter; alert at 60 s. */
    private static RelaySettings controls(int breakerFailures) {
        return new RelaySettings(breakerFailures, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(30), 0.0, Duration.ofSeconds(60));
    }

    private static PostgresOutboxRelay controlledRelay(FakeOutboxAccess access, RelayTenantContext context,
            PublishPort port, RelaySettings settings, MeterRegistry meters, ObservationRegistry observations,
            Clock clock, RelayAlerts alerts) {
        return new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context, new FakeTransactions(access), port,
                ACK_TIMEOUT, BATCH, settings, meters, observations, clock, alerts);
    }

    @Test
    void aRowTheBrokerAcknowledgedIsNotPublishedAgainByTheNextCycle() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 4, 1, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort port = new InMemoryPublishPort();
        MutableClock clock = new MutableClock();
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(5), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, clock, RelayAlerts.LOG);

        relay.runOnce();
        port.reset();
        clock.advance(Duration.ofMinutes(1));
        relay.runOnce();

        assertThat(port.published()).as("the acknowledged and marked row is never sent again").isEmpty();
        assertThat(access.isPublished(1)).isTrue();
    }

    @Test
    void anAcknowledgedRowWhoseMarkFailedIsMarkedByTheNextCycleWithoutBeingSentAgain() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 5, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 5, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 5, 3, OutboxTopic.MESSAGE_ACCEPTED);
        access.failMarkingAt(2); // the broker acknowledged rows 1 and 2, then the database failed the mark
        InMemoryPublishPort port = new InMemoryPublishPort();
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(5), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, new MutableClock(), RelayAlerts.LOG);

        assertThatThrownBy(relay::runOnce).isInstanceOf(IllegalStateException.class);
        assertThat(access.publishedCount()).as("the failed mark transaction applied nothing").isZero();

        access.clearCrash();
        port.reset();
        int marked = relay.runOnce();

        assertThat(port.published()).extracting(OutboundRecord::outboxId)
                .as("rows 1 and 2 were acknowledged by the broker - the relay never re-sends them (ADR-005 D12)")
                .containsExactly(3L);
        assertThat(marked).as("all three rows are marked now").isEqualTo(3);
        assertThat(access.publishedCount()).isEqualTo(3);
    }

    @Test
    void anUnacknowledgedRowIsTriedAgainOnlyAfterItsBucketsBackoffAndOtherBucketsKeepPublishing() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 4, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 4, 2, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 6, 3, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort port = new InMemoryPublishPort();
        port.refuseAcknowledgeOf(1);
        MutableClock clock = new MutableClock();
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(5), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, clock, RelayAlerts.LOG);

        relay.runOnce();
        assertThat(port.published()).extracting(OutboundRecord::outboxId)
                .as("bucket 4 stops at its unacknowledged row; bucket 6 publishes").containsExactly(1L, 3L);

        port.reset();
        clock.advance(Duration.ofMillis(500));
        relay.runOnce();
        assertThat(port.published()).as("inside the 1 s backoff the bucket is not tried again").isEmpty();

        clock.advance(Duration.ofMillis(600));
        relay.runOnce();
        assertThat(port.published()).extracting(OutboundRecord::outboxId)
                .as("after the backoff the bucket is tried again, in outbox-id order").containsExactly(1L, 2L);
        assertThat(access.publishedCount()).isEqualTo(3);
    }

    @Test
    void aPublishThatThrowsIsAFailedPublishThatStopsOnlyItsBucket() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 4, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 6, 2, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort delegate = new InMemoryPublishPort();
        PublishPort port = new PublishPort() {
            @Override
            public PublishReceipt publish(OutboundRecord record) {
                if (record.outboxId() == 1) {
                    throw new IllegalStateException("producer closed");
                }
                return delegate.publish(record);
            }

            @Override
            public boolean acknowledge(PublishReceipt receipt, Duration timeout) {
                return delegate.acknowledge(receipt, timeout);
            }
        };
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(5), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, new MutableClock(), RelayAlerts.LOG);

        int marked = relay.runOnce();

        assertThat(marked).as("the cycle survives the throwing publish; bucket 6 still publishes").isEqualTo(1);
        assertThat(access.isPublished(1)).as("the row whose publish threw waits in the outbox").isFalse();
        assertThat(access.isPublished(2)).isTrue();
    }

    @Test
    void theKafkaBreakerOpensAfterConsecutiveFailedPublishesTheRelayStopsPollingAndRowsWaitInOrder() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        access.add(TENANT_A, 4, 1, OutboxTopic.MESSAGE_ACCEPTED);
        access.add(TENANT_A, 4, 2, OutboxTopic.MESSAGE_ACCEPTED);
        InMemoryPublishPort port = new InMemoryPublishPort();
        port.refuseAcknowledgeOf(1); // Kafka is down: nothing is acknowledged
        MutableClock clock = new MutableClock();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(3), meters,
                ObservationRegistry.NOOP, clock, RelayAlerts.LOG);

        for (int cycle = 0; cycle < 3; cycle++) { // 3 consecutive failures, each after its bucket backoff
            relay.runOnce();
            clock.advance(Duration.ofSeconds(5));
        }
        int claimsBefore = access.claimCount();
        relay.runOnce();

        assertThat(access.claimCount()).as("the open Kafka breaker stops the relay polling the outbox")
                .isEqualTo(claimsBefore);
        assertThat(meters.get("resilience4j.circuitbreaker.state").tags("name", "kafka", "state", "open")
                .gauge().value()).as("the one Kafka breaker is open").isEqualTo(1.0);
        assertThat(meters.get("platform.outbox.unpublished.rows").gauge().value())
                .as("while the breaker is open the backlog is still measured: both rows wait").isEqualTo(2.0);
        assertThat(access.publishedCount()).isZero();

        port.reset(); // Kafka is back
        clock.advance(Duration.ofSeconds(31)); // the open period passes - a half-open trial is allowed
        relay.runOnce();

        assertThat(port.published()).extracting(OutboundRecord::outboxId)
                .as("after Kafka returns every waiting row is published, in order").containsExactly(1L, 2L);
        assertThat(access.publishedCount()).isEqualTo(2);
    }

    @Test
    void everyPublishIsObservableBacklogGaugesOutcomeTimersAndASpanThatRestoresTheTraceparent() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        MutableClock clock = new MutableClock();
        access.add(TENANT_A, 3, 1, OutboxTopic.MESSAGE_ACCEPTED, clock.instant().minusSeconds(5));
        access.traceparent(1, TRACEPARENT);
        access.add(TENANT_A, 8, 2, OutboxTopic.MESSAGE_ACCEPTED, clock.instant().minusSeconds(42));
        InMemoryPublishPort port = new InMemoryPublishPort();
        port.refuseAcknowledgeOf(2);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(capturing(stopped));
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(5), meters, observations, clock,
                RelayAlerts.LOG);

        relay.runOnce();

        assertThat(meters.get("platform.outbox.unpublished.rows").gauge().value())
                .as("unpublished due rows after the cycle").isEqualTo(1.0);
        assertThat(meters.get("platform.outbox.oldest.unpublished.age.seconds").gauge().value())
                .as("the oldest due unpublished row's age").isEqualTo(42.0);
        assertThat(meters.get("platform.outbox.publish").tags("bucket", "3", "outcome", "acknowledged").timer()
                .count()).as("publish latency per bucket and outcome").isEqualTo(1);
        assertThat(meters.get("platform.outbox.publish").tags("bucket", "8", "outcome", "failed").timer()
                .count()).as("publish errors per bucket").isEqualTo(1);
        assertThat(stopped).extracting(Observation.Context::getName).as("one finished span per publish")
                .containsExactly("platform.outbox.publish", "platform.outbox.publish");
        List<ReceiverContext<?>> spans = stopped.stream()
                .filter(observation -> observation instanceof ReceiverContext<?>)
                .<ReceiverContext<?>>map(observation -> (ReceiverContext<?>) observation).toList();
        assertThat(spans).as("every publish span is a receiver of the row's stored trace context").hasSize(2);
        assertThat(spans).extracting(PostgresOutboxRelayTest::restoredTraceparent)
                .as("the span restores the row's stored traceparent (ADR-005 Decision 9)")
                .contains(TRACEPARENT);
    }

    @Test
    void theOutboxAgeAlertFiresOnceWhenTheOldestDueRowPassesTheThresholdAndAgainOnlyAfterItCleared() {
        RelayTenantContext context = new RelayTenantContext();
        FakeOutboxAccess access = new FakeOutboxAccess(context);
        MutableClock clock = new MutableClock();
        access.add(TENANT_A, 4, 1, OutboxTopic.MESSAGE_ACCEPTED, clock.instant().minusSeconds(30));
        InMemoryPublishPort port = new InMemoryPublishPort();
        port.refuseAcknowledgeOf(1);
        List<Duration> alerts = new ArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PostgresOutboxRelay relay = controlledRelay(access, context, port, controls(50), meters,
                ObservationRegistry.NOOP, clock, (age, rows) -> alerts.add(age));

        relay.runOnce();
        assertThat(alerts).as("30 s is below the 60 s threshold").isEmpty();

        clock.advance(Duration.ofSeconds(31));
        relay.runOnce();
        clock.advance(Duration.ofSeconds(5));
        relay.runOnce();
        assertThat(alerts).as("the alert fires once when the oldest row passes 60 s, not on every cycle")
                .hasSize(1).allSatisfy(age -> assertThat(age).isGreaterThanOrEqualTo(Duration.ofSeconds(60)));
        assertThat(meters.get("platform.alerts.fired").tags("alert", "outbox_age").counter().count())
                .isEqualTo(1.0);

        port.reset();
        clock.advance(Duration.ofSeconds(31));
        relay.runOnce(); // the backlog clears
        access.add(TENANT_A, 4, 2, OutboxTopic.MESSAGE_ACCEPTED, clock.instant().minusSeconds(90));
        port.refuseAcknowledgeOf(2);
        relay.runOnce();
        assertThat(alerts).as("a new episode raises the alert again").hasSize(2);
    }

    /** Collects every stopped observation - the span a tracing handler would export. */
    private static ObservationHandler<Observation.Context> capturing(List<Observation.Context> stopped) {
        return new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        };
    }

    private static String restoredTraceparent(ReceiverContext<?> context) {
        @SuppressWarnings("unchecked")
        ReceiverContext<Object> receiver = (ReceiverContext<Object>) context;
        return receiver.getGetter().get(receiver.getCarrier(), "traceparent");
    }

    /** A clock the test moves by hand - the relay's backoff, breaker and alert all read it. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-13T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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
        private final List<String> operationLog = new ArrayList<>();
        private long crashAtMarkId;
        private final java.util.Map<Long, String> traceparents = new java.util.HashMap<>();
        private int claimCount;

        FakeOutboxAccess(RelayTenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        void add(UUID tenant, int bucket, long id, OutboxTopic topic) {
            add(tenant, bucket, id, topic, Instant.now().minusSeconds(1));
        }

        void add(UUID tenant, int bucket, long id, OutboxTopic topic, Instant availableAt) {
            rows.add(new Row(tenant, bucket, id, topic, availableAt));
        }

        void traceparent(long id, String traceparent) {
            traceparents.put(id, traceparent);
        }

        int claimCount() {
            return claimCount;
        }

        void failMarkingAt(long id) {
            crashAtMarkId = id;
        }

        void clearCrash() {
            crashAtMarkId = 0;
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

        /** Every lock and claim call, in call order - the lock-before-claim order of one bucket transaction. */
        List<String> operationLog() {
            return List.copyOf(operationLog);
        }

        List<String> lockedBuckets() {
            return operationLog.stream().filter(operation -> operation.startsWith("lock:")).toList();
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
        public void lockKeyBucket(int keyBucket) {
            operationLog.add("lock:" + keyBucket);
        }

        @Override
        public List<ClaimedOutboxRow> claimUnpublished(int keyBucket, Instant availableAtOrBefore, int limit) {
            UUID tenant = tenantContext.requireTenantId();
            claimTenants.add(tenant);
            claimCount++;
            operationLog.add("claim:" + keyBucket);
            // The fake filters by the BOUND tenant exactly as the tenant predicate and row-level security do in
            // the real table (V2_0_1__outbox.sql); the container test proves the database enforces it.
            return rows.stream()
                    .filter(row -> row.tenant.equals(tenant))
                    .filter(row -> !row.published)
                    .filter(row -> row.bucket == keyBucket)
                    .filter(row -> !row.availableAt.isAfter(availableAtOrBefore))
                    .sorted((left, right) -> Long.compare(left.id, right.id))
                    .limit(limit)
                    .map(row -> new ClaimedOutboxRow(row.id, row.topic, aggregate(row.id), "key-" + row.id,
                            traceparents.get(row.id)))
                    .toList();
        }

        @Override
        public OutboxBacklog backlog(Instant now) {
            UUID tenant = tenantContext.requireTenantId();
            List<Row> due = rows.stream().filter(row -> row.tenant.equals(tenant)).filter(row -> !row.published)
                    .filter(row -> !row.availableAt.isAfter(now)).toList();
            return new OutboxBacklog(due.size(),
                    due.stream().map(row -> row.availableAt).min(Instant::compareTo).orElse(null));
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
