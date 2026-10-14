package io.integrationplatform.ingest.internal.relay;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.OutboxRelay;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    /** The one publish span's name; its timer carries the bucket and the outcome as tags (ADR-005 D9, D12). */
    static final String PUBLISH_SPAN = "platform.outbox.publish";

    /** The one Kafka breaker's state gauge, named for the resilience4j-micrometer integration (E8-F1-S3). */
    static final String BREAKER_STATE_GAUGE = "resilience4j.circuitbreaker.state";

    /** The controlled publish's results. */
    private enum RowPublish {
        ACKNOWLEDGED, FAILED
    }

    /** The synthetic failure a refused acknowledge records on the breaker - the broker never confirmed. */
    private static final class AcknowledgeRefusedException extends RuntimeException {
    }

    private final ActiveTenantSource tenantSource;
    private final OutboxRowAccess access;
    private final RelayTenantContext tenantContext;
    private final TransactionOperations transactions;
    private final PublishPort publishPort;
    private final Duration acknowledgeTimeout;
    private final int batchSize;
    // ----- the T-191 controls (ADR-005 Decision 12); null/unused when the legacy constructor built the relay -----
    private final RelaySettings settings;
    private final Clock clock;
    private final RelayAlerts alerts;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final CircuitBreaker breaker;
    /** The one Kafka breaker's state gauges read the live state; registered once per name. */
    private final boolean controlled;
    /** Rows whose publish this instance has ACKNOWLEDGED to the broker: never re-sent, only marked again. */
    private final Set<Long> acknowledgedRows = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AtomicLong> unpublishedGauge = new HashMap<>();
    private final Map<UUID, AtomicLong> oldestAgeGauge = new HashMap<>();
    private final Map<UUID, Boolean> ageAlertActive = new HashMap<>();
    private final Map<String, Integer> backoffAttempts = new HashMap<>();
    private final Map<String, Instant> backoffNextAttempt = new HashMap<>();

    public PostgresOutboxRelay(ActiveTenantSource tenantSource, OutboxRowAccess access,
            RelayTenantContext tenantContext, TransactionOperations transactions, PublishPort publishPort,
            Duration acknowledgeTimeout, int batchSize) {
        this(tenantSource, access, tenantContext, transactions, publishPort, acknowledgeTimeout, batchSize, null,
                null, null, Clock.systemUTC(), null);
    }

    public PostgresOutboxRelay(ActiveTenantSource tenantSource, OutboxRowAccess access,
            RelayTenantContext tenantContext, TransactionOperations transactions, PublishPort publishPort,
            Duration acknowledgeTimeout, int batchSize, RelaySettings settings, MeterRegistry meters,
            ObservationRegistry observations, Clock clock, RelayAlerts alerts) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (acknowledgeTimeout.isZero() || acknowledgeTimeout.isNegative()) {
            throw new IllegalArgumentException("acknowledgeTimeout must be positive");
        }
        if (settings != null) {
            Objects.requireNonNull(clock, "the controlled relay needs a clock (the controls measure time)");
        }
        this.tenantSource = Objects.requireNonNull(tenantSource, "tenantSource");
        this.access = Objects.requireNonNull(access, "access");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.publishPort = Objects.requireNonNull(publishPort, "publishPort");
        this.acknowledgeTimeout = Objects.requireNonNull(acknowledgeTimeout, "acknowledgeTimeout");
        this.batchSize = batchSize;
        this.settings = settings;
        this.meters = meters;
        this.observations = observations;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.alerts = alerts == null ? RelayAlerts.LOG : alerts;
        this.controlled = settings != null;
        if (controlled && meters != null) {
            this.breaker = CircuitBreaker.of("kafka", breakerConfig(settings.breakerFailureThreshold(),
                    settings.breakerWaitInOpen(), this.clock));
            registerBreakerStateGauges(breaker, meters);
        } else {
            this.breaker = null;
        }
    }

    /** ADR-005 Decision 12: one Kafka breaker whose window is the threshold - it opens on that many
     * consecutive failed publishes, waits {@code waitInOpen}, then lets a half-open trial through. The
     * breaker measures waiting time on the relay's clock, so a restarted clock moves it too. */
    private static CircuitBreakerConfig breakerConfig(int threshold, Duration waitInOpen, Clock clock) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(threshold)
                .minimumNumberOfCalls(threshold)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(waitInOpen)
                .clock(clock)
                .build();
    }

    /** The state gauges the resilience4j-micrometer integration is named after; the operators' dashboards
     * and the alert rules read {@code state="open"} for the breaker's name. */
    private static void registerBreakerStateGauges(CircuitBreaker breaker, MeterRegistry meters) {
        for (CircuitBreaker.State state : List.of(CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.DISABLED)) {
            Gauge.builder(BREAKER_STATE_GAUGE, breaker,
                            candidate -> candidate.getState() == state ? 1.0d : 0.0d)
                    .tag("name", breaker.getName()).tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
                    .register(meters);
        }
    }

    @Override
    public int runOnce() {
        int marked = 0;
        for (UUID tenant : tenantSource.activeTenantIds()) {
            tenantContext.bind(tenant);
            try {
                boolean permitted = breaker == null || breaker.tryAcquirePermission();
                if (permitted) {
                    for (int keyBucket = 0; keyBucket < KEY_BUCKETS; keyBucket++) {
                        if (breaker != null && breaker.getState() == CircuitBreaker.State.OPEN) {
                            break; // the breaker opened mid-cycle: the remaining buckets wait with the rows
                        }
                        if (inBackoff(tenant, keyBucket)) {
                            continue; // this bucket's unacknowledged row is not due to be tried again
                        }
                        final int bucket = keyBucket;
                        marked += transactions.execute(status -> publishBucket(tenant, bucket));
                    }
                }
                measureBacklog(tenant);
            } finally {
                tenantContext.unbind();
            }
        }
        return marked;
    }

    private int publishBucket(UUID tenant, int keyBucket) {
        // T-179 (ADR fix m-817272 B3): the bucket work is serialized before the claim, so a second relay
        // instance cannot SKIP LOCKED its way ahead of this transaction's unpublished rows and publish
        // them out of sequence (E8-C2; the container sibling proves the two-instance race).
        access.lockKeyBucket(keyBucket);
        List<ClaimedOutboxRow> rows = access.claimUnpublished(keyBucket, Instant.now(), batchSize);
        int marked = 0;
        boolean failed = false;
        for (ClaimedOutboxRow row : rows) {
            // T-191: what this instance already delivered and the broker acknowledged must never be sent
            // again - a crashed cycle left it unmarked, and the next cycle only re-marks it (ADR-005 D12).
            if (controlled && acknowledgedRows.contains(row.outboxId())) {
                if (!access.markPublished(row.outboxId())) {
                    break; // the row is no longer ours to mark (already published or gone)
                }
                marked++;
                continue;
            }
            OutboundRecord record = new OutboundRecord(row.topic().topicName(), row.recordKey(),
                    row.outboxId(), row.aggregateId(), row.traceparent());
            if (controlled) {
                RowPublish result = publishWithControls(tenant, keyBucket, record, row);
                if (result != RowPublish.ACKNOWLEDGED) {
                    failed = true;
                    break; // an unacknowledged row stops only its bucket (ADR-005 Decision 11)
                }
                // the broker confirmed before any mark could fail: a crashed transaction's next cycle
                // only re-marks this row, it never sends it again (ADR-005 D12)
                acknowledgedRows.add(row.outboxId());
            } else {
                PublishReceipt receipt = publishPort.publish(record);
                if (!publishPort.acknowledge(receipt, acknowledgeTimeout)) {
                    break;
                }
            }
            if (!access.markPublished(row.outboxId())) {
                break; // the row is no longer ours to mark (already published or gone): stop the bucket
            }
            marked++;
        }
        if (controlled && !failed) {
            clearBucketBackoff(tenant, keyBucket); // a fully published bucket restarts its backoff ladder
        }
        return marked;
    }

    /** The controlled publish: one observation per attempt (the span restores the row's traceparent and the
     * DefaultMeterObservationHandler turns bucket/outcome into the publish timer), the breaker records the
     * outcome, and a failed publish arms the bucket's backoff. */
    private RowPublish publishWithControls(UUID tenant, int keyBucket, OutboundRecord record,
            ClaimedOutboxRow row) {
        Observation observation = publishObservation(keyBucket, row.traceparent());
        observation.lowCardinalityKeyValue("outcome", "failed");
        Throwable failure = null;
        observation.start();
        try {
            PublishReceipt receipt = publishPort.publish(record);
            if (publishPort.acknowledge(receipt, acknowledgeTimeout)) {
                observation.lowCardinalityKeyValue("outcome", "acknowledged");
            } else {
                failure = new AcknowledgeRefusedException();
            }
        } catch (RuntimeException thrown) {
            failure = thrown;
            observation.error(thrown);
        } finally {
            observation.stop();
        }
        if (failure != null) {
            breaker.onError(0L, TimeUnit.MILLISECONDS, failure);
            armBucketBackoff(tenant, keyBucket);
            return RowPublish.FAILED;
        }
        breaker.onSuccess(0L, TimeUnit.MILLISECONDS);
        return RowPublish.ACKNOWLEDGED;
    }

    /** The publish span's receiver; its carrier is primed with the row's stored traceparent (ADR-005 D9). */
    private Observation publishObservation(int keyBucket, String traceparent) {
        if (observations == null || ObservationRegistry.NOOP.equals(observations)) {
            return Observation.NOOP;
        }
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null) {
            carrier.put("traceparent", traceparent);
        }
        ReceiverContext<Map<String, String>> context =
                new ReceiverContext<>((got, key) -> got.get(key));
        context.setCarrier(carrier);
        Observation observation = Observation.createNotStarted(PUBLISH_SPAN, () -> context, observations);
        observation.lowCardinalityKeyValue("bucket", String.valueOf(keyBucket));
        return observation;
    }

    // ----- the bucket backoff (ADR-005 Decision 12: an unacknowledged row's bucket is retried later) -----

    private int bucketAttempt(UUID tenant, int keyBucket) {
        return backoffAttempts.getOrDefault(backoffKey(tenant, keyBucket), 0);
    }

    private boolean inBackoff(UUID tenant, int keyBucket) {
        Instant next = backoffNextAttempt.get(backoffKey(tenant, keyBucket));
        return next != null && clock.instant().isBefore(next);
    }

    private void armBucketBackoff(UUID tenant, int keyBucket) {
        String key = backoffKey(tenant, keyBucket);
        int attempt = bucketAttempt(tenant, keyBucket) + 1;
        double doubled = (double) settings.backoffInitial().toMillis() * Math.pow(2, attempt - 1);
        long base = (long) Math.min(doubled, settings.backoffMax().toMillis());
        double jitter = settings.backoffRandomization() <= 0 ? 1.0d
                : ThreadLocalRandom.current().nextDouble(1 - settings.backoffRandomization(),
                        1 + settings.backoffRandomization());
        backoffAttempts.put(key, attempt);
        backoffNextAttempt.put(key, clock.instant().plusMillis((long) (base * jitter)));
    }

    private void clearBucketBackoff(UUID tenant, int keyBucket) {
        String key = backoffKey(tenant, keyBucket);
        backoffAttempts.remove(key);
        backoffNextAttempt.remove(key);
    }

    private static String backoffKey(UUID tenant, int keyBucket) {
        return tenant + ":" + keyBucket;
    }

    // ----- the backlog gauges and the outbox-age alert (ADR-005 Decision 12) -----

    private void measureBacklog(UUID tenant) {
        if (!controlled || meters == null) {
            return;
        }
        OutboxBacklog backlog = transactions.execute(status -> access.backlog(Instant.now()));
        setGauge(unpublishedGauge, tenant, "platform.outbox.unpublished.rows", backlog.unpublishedRows());
        long ageSeconds = backlog.oldestDueAt() == null ? 0
                : Math.max(0, Duration.between(backlog.oldestDueAt(), clock.instant()).toSeconds());
        setGauge(oldestAgeGauge, tenant, "platform.outbox.oldest.unpublished.age.seconds", ageSeconds);
        boolean active = ageAlertActive.getOrDefault(tenant, Boolean.FALSE);
        if (unpublishedAgeExceeded(backlog, ageSeconds)) {
            if (!active) {
                alerts.outboxAgeExceeded(Duration.ofSeconds(ageSeconds), backlog.unpublishedRows());
                meters.counter("platform.alerts.fired", "alert", "outbox_age").increment();
                ageAlertActive.put(tenant, Boolean.TRUE);
            }
        } else if (backlog.oldestDueAt() == null) {
            ageAlertActive.put(tenant, Boolean.FALSE); // the backlog cleared: the next episode fires again
        }
    }

    private boolean unpublishedAgeExceeded(OutboxBacklog backlog, long ageSeconds) {
        return backlog.unpublishedRows() > 0 && ageSeconds >= settings.outboxAgeAlert().toSeconds();
    }

    private void setGauge(Map<UUID, AtomicLong> gauges, UUID tenant, String name, long value) {
        AtomicLong holder = gauges.get(tenant);
        if (holder == null) {
            holder = new AtomicLong();
            Gauge.builder(name, holder, AtomicLong::doubleValue).tag("tenant", tenant.toString())
                    .register(meters);
            gauges.put(tenant, holder);
        }
        holder.set(value);
    }
}
