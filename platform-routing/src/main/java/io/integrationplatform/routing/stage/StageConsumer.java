package io.integrationplatform.routing.stage;

import io.integrationplatform.ingest.port.CorrelationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * The stage consumer base (T-111, T-113, T-181; E8-F1). Per record, in order:
 * <ol>
 *   <li>read the ids-only contract - anything unreadable is written to the one poison topic once and the
 *       partition moves on (T-113, E8-C6/E8-C7);</li>
 *   <li>resolve the row's tenant through the protected path ({@link ProtectedRows#tenantFor}, the
 *       {@code ipaas_work_tenant} definer function, ADR-011 Decision 8) - never from a record header or value,
 *       so a forged tenant is ignored (E8-C4); a record naming no row does nothing but is acknowledged
 *       (backlog.md:2051-2054);</li>
 *   <li>check the row is visible under the bound tenant's row-level security, then hand the work to the
 *       {@link Handler};</li>
 *   <li>commit the record's offset <b>only after</b> its work succeeded.</li>
 * </ol>
 *
 * <p>A transient database error pauses the partition, retries the same record after a back-off and resumes -
 * the offset is never committed until the work succeeds, so no record is skipped and none is poisoned for a
 * transient database error (T-181, ADR fix m-817272 B4). Any handler failure is treated as transient: conservative,
 * never work-losing - poison is reserved for unreadable records (E8-C6).
 *
 * <p>R12: this class is the Kafka client boundary of the base - it imports kafka-clients and nothing behind it
 * imports Kafka, so E9's stage 1 and stage 2 handlers stay Kafka-free.
 */
public final class StageConsumer {

    /** What one record's handling ended in - the base acts on each value's rule above. */
    public enum Outcome {
        /** The handler ran under the row's tenant - offset committed. */
        CONSUMED,
        /** Unreadable against the record contract - written to poison once, offset committed. */
        POISONED,
        /** No row the record names - nothing processed, offset committed. */
        NO_ROW,
        /** A transient database error - offset not committed, the partition paused for a back-off. */
        TRANSIENT
    }

    /** The row decides the tenant: resolves through the RLS-protected work functions, never from the record. */
    public interface ProtectedRows {

        /** The row's tenant ({@code ipaas_work_tenant(kind, id)}), or {@code null} when no row names this id. */
        UUID tenantFor(WorkKind kind, UUID id);

        /** Whether the row is visible to the tenant under row-level security. */
        boolean visibleTo(UUID tenantId, WorkKind kind, UUID id);
    }

    /** The stage's work (E9 supplies the handlers): called once per readable record naming an existing row. */
    public interface Handler {

        void onWork(WorkRecord record, UUID tenantId);
    }

    /** The one poison write (T-113): the raw record bytes, once, never a retry path. */
    public interface PoisonSink {

        void write(byte[] key, byte[] value, String reason);
    }

    /** The wait between retries; the base sleeps it instead of busy-waiting. */
    public interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    /** The one per-record span's name (T-191; ADR-005 Decision 9: the record's traceparent is restored). */
    static final String RECORD_SPAN = "platform.stage.record";

    /** The group's database breaker state gauge, named for the resilience4j-micrometer conventions (T-191). */
    static final String BREAKER_STATE_GAUGE = "resilience4j.circuitbreaker.state";

    private record Pause(int attempts, Instant resumeAt) {
    }

    private final Consumer<byte[], byte[]> consumer;
    private final WorkRecordParser parser;
    private final ProtectedRows rows;
    private final Handler handler;
    private final PoisonSink poison;
    private final java.util.function.IntFunction<Duration> backoff;
    private final Duration pollTimeout;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<TopicPartition, Pause> paused = new HashMap<>();
    // ----- the T-191 controls (ADR-005 Decision 12); null/absent when the legacy constructor built the base -----
    private final StageSettings settings;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final io.micrometer.observation.ObservationRegistry observations;
    private final StageAlerts alerts;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker breaker;
    private boolean breakerPausedAll;
    /** The single consumer thread reads these back for the alert and the breaker; never shared. */
    private String lastUnreadableReason;
    private Throwable lastTransientCause;

    public StageConsumer(Consumer<byte[], byte[]> consumer, WorkRecordParser parser, ProtectedRows rows,
            Handler handler, PoisonSink poison, java.util.function.IntFunction<Duration> backoff,
            Duration pollTimeout, Clock clock, Sleeper sleeper) {
        this(consumer, parser, rows, handler, poison, backoff, pollTimeout, clock, sleeper, null, null, null, null);
    }

    public StageConsumer(Consumer<byte[], byte[]> consumer, WorkRecordParser parser, ProtectedRows rows,
            Handler handler, PoisonSink poison, java.util.function.IntFunction<Duration> backoff,
            Duration pollTimeout, Clock clock, Sleeper sleeper, StageSettings settings,
            io.micrometer.core.instrument.MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations, StageAlerts alerts) {
        this.consumer = consumer;
        this.parser = parser;
        this.rows = rows;
        this.handler = handler;
        this.poison = poison;
        this.backoff = backoff;
        this.pollTimeout = pollTimeout;
        this.clock = clock;
        this.sleeper = sleeper;
        this.settings = settings;
        this.meters = meters;
        this.observations = observations;
        this.alerts = alerts;
        if (settings != null && meters != null) {
            this.breaker = io.github.resilience4j.circuitbreaker.CircuitBreaker
                    .of("database-" + settings.group(), io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
                            .custom()
                            .slidingWindowSize(settings.breakerFailureThreshold())
                            .minimumNumberOfCalls(settings.breakerFailureThreshold())
                            .failureRateThreshold(50f)
                            .waitDurationInOpenState(settings.breakerWaitInOpen())
                            .clock(clock)
                            .build());
            registerBreakerStateGauges(breaker, meters);
        } else {
            this.breaker = null;
        }
    }

    /** The state gauges the resilience4j-micrometer integration is named after - one per state of the
     * group's database breaker (T-191, E8-F1-S3); the alert rules and dashboards read {@code open}. */
    private static void registerBreakerStateGauges(io.github.resilience4j.circuitbreaker.CircuitBreaker breaker,
            io.micrometer.core.instrument.MeterRegistry meters) {
        for (io.github.resilience4j.circuitbreaker.CircuitBreaker.State state : io.github.resilience4j.circuitbreaker.CircuitBreaker
                .State.values()) {
            io.micrometer.core.instrument.Gauge
                    .builder(BREAKER_STATE_GAUGE, breaker,
                            candidate -> candidate.getState() == state ? 1.0d : 0.0d)
                    .tag("name", breaker.getName()).tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
                    .register(meters);
        }
    }

    /**
     * The stage consumer's connection settings (T-191, E8-F1-S3; ADR-005 Decision 12): the group, no auto
     * commit (the base commits per handled record only), a poll interval the handler can work inside, and
     * every blocking client call bounded. The key and value deserializers stay the record contract's byte arrays.
     */
    public static Map<String, Object> consumerConfig(String bootstrapServers, String group) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        return config;
    }

    /** Handles one record; the outcome decides the offset and the partition rule of the class javadoc. Keeps
     * the unreadable reason and the transient cause back for the poison alert and the breaker (one consumer
     * thread reads them right after; never shared). */
    public Outcome handleOne(ConsumerRecord<byte[], byte[]> record) {
        WorkRecord contract;
        try {
            contract = parser.parse(record.value(), record.headers());
        } catch (UnreadableRecordException e) {
            // A failed poison write pauses instead of committing: the record is never lost (T-113).
            lastUnreadableReason = e.getMessage();
            try {
                poison.write(record.key(), record.value(), e.getMessage());
            } catch (RuntimeException poisonFailure) {
                lastTransientCause = poisonFailure;
                return Outcome.TRANSIENT;
            }
            return Outcome.POISONED;
        }
        WorkKind kind = WorkKind.of(contract.type());
        // T107 (E12-F1-S1): the record's traceparent is the journey - every log line of this record's work carries
        // its correlation id, the row's id and (once resolved from the row) its tenant; outbox rows the handler
        // appends inherit the traceparent, the next hop's Kafka header. Nothing is left on the consumer thread.
        try (CorrelationContext.Scope journey = CorrelationContext.open(contract.traceparent())) {
            CorrelationContext.tag(kind == WorkKind.MESSAGE ? CorrelationContext.MESSAGE_ID
                    : CorrelationContext.DELIVERY_ID, contract.id());
            UUID tenantId = rows.tenantFor(kind, contract.id());
            if (tenantId == null) {
                return noRow(kind, contract);
            }
            CorrelationContext.tag(CorrelationContext.TENANT_ID, tenantId);
            if (!rows.visibleTo(tenantId, kind, contract.id())) {
                return noRow(kind, contract);
            }
            handler.onWork(contract, tenantId);
        } catch (RuntimeException e) {
            lastTransientCause = e;
            return Outcome.TRANSIENT;
        }
        return Outcome.CONSUMED;
    }

    /**
     * One poll-and-process turn: resumes due pauses, polls, processes every partition's records in order,
     * commits each record only after its outcome is final, and pauses with a back-off on the first transient
     * error of a partition. With the T-191 controls (ADR-005 Decision 12): every record is one span that
     * restores its traceparent and one outcome counter; the group's database breaker opens after
     * {@code breakerFailureThreshold} consecutive transient errors, pauses ALL the group's partitions for the
     * open period and lets a half-open trial through after it; every poison arrival raises its alert. Returns
     * the number of records seen.
     */
    public int drainOnce() throws InterruptedException {
        if (breaker != null && !breaker.tryAcquirePermission()) {
            // The breaker is open and the open period has not passed: none of the group's partitions is
            // polled. The wait is bounded like the empty poll below, never a hot spin.
            sleeper.sleep(pollTimeout);
            return 0;
        }
        if (breaker != null) {
            if (breakerPausedAll) {
                // The open period passed (the half-open trial is the permission just granted): resume what
                // the breaker paused; partitions still in a recorded back-off stay paused for their resume.
                List<TopicPartition> resumeBack = new ArrayList<>(consumer.assignment());
                resumeBack.removeAll(paused.keySet());
                if (!resumeBack.isEmpty()) {
                    consumer.resume(resumeBack);
                }
            }
            breakerPausedAll = false; // permission is back: a later open pauses and counts again
        }
        int seen = 0;
        List<TopicPartition> due = new ArrayList<>();
        for (Map.Entry<TopicPartition, Pause> pause : paused.entrySet()) {
            if (!clock.instant().isBefore(pause.getValue().resumeAt)) {
                due.add(pause.getKey());
            }
        }
        if (!due.isEmpty()) {
            consumer.resume(due);
        }

        ConsumerRecords<byte[], byte[]> records = consumer.poll(pollTimeout);
        List<TopicPartition> batch = new ArrayList<>(records.partitions());
        for (int index = 0; index < batch.size(); index++) {
            TopicPartition partition = batch.get(index);
            if (breaker != null && breaker.getState()
                    == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN) {
                // The breaker opened mid-drain: the partitions of this poll not yet processed are seeked
                // back to their first record and paused - the half-open trial redelivers them, none is
                // skipped (the fetch moved their position past records no code has seen).
                for (TopicPartition skip : batch.subList(index, batch.size())) {
                    consumer.seek(skip, records.records(skip).get(0).offset());
                    consumer.pause(List.of(skip));
                }
                break;
            }
            for (ConsumerRecord<byte[], byte[]> record : records.records(partition)) {
                seen++;
                Outcome outcome = handleWithControls(record);
                if (outcome == Outcome.TRANSIENT) {
                    recordBreakerFailure();
                    if (breaker != null && breaker.getState()
                            == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN && !breakerPausedAll) {
                        breakerOpened();
                    }
                    Pause previous = paused.get(partition);
                    int attempt = previous == null ? 1 : previous.attempts() + 1;
                    paused.put(partition, new Pause(attempt, clock.instant().plus(backoff.apply(attempt))));
                    // The record is retried after the back-off and never skipped: seek back to it, so the fetch
                    // position resets and the broker redelivers it (and any behind it) when the partition resumes.
                    consumer.seek(partition, record.offset());
                    consumer.pause(List.of(partition));
                    countPause("transient");
                    break;
                }
                if (breaker != null) {
                    breaker.onSuccess(0L, TimeUnit.MILLISECONDS);
                }
                if (paused.remove(partition) != null) {
                    // A retried record succeeded - the partition keeps its work (T-181: pauses and resumes).
                    consumer.resume(List.of(partition));
                }
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
            }
        }

        if (seen == 0) {
            // Nothing arrived - wait for the next pending pause's resume, or one poll interval, never a hot spin.
            long waitMillis;
            if (paused.isEmpty()) {
                waitMillis = pollTimeout.toMillis();
            } else {
                Instant earliest =
                        paused.values().stream().map(Pause::resumeAt).min(Instant::compareTo).orElseThrow();
                waitMillis = earliest.toEpochMilli() - clock.instant().toEpochMilli();
            }
            if (waitMillis > 0) {
                sleeper.sleep(Duration.ofMillis(Math.min(waitMillis, pollTimeout.toMillis() * 4)));
            }
        }
        return seen;
    }

    /** One record: with the controls it is an outcome counter, a poison alert and one traceparent span. */
    private Outcome handleWithControls(ConsumerRecord<byte[], byte[]> record) {
        if (breaker == null) {
            return handleOne(record);
        }
        io.micrometer.observation.Observation observation = recordObservation(record);
        observation.start();
        try {
            Outcome outcome = handleOne(record);
            countOutcome(outcome, record.topic());
            if (outcome == Outcome.POISONED) {
                countPoisonArrival(record);
            }
            return outcome;
        } finally {
            observation.stop();
        }
    }

    private void recordBreakerFailure() {
        if (breaker == null) {
            return;
        }
        Throwable cause = lastTransientCause;
        breaker.onError(0L, TimeUnit.MILLISECONDS, cause == null ? new IllegalStateException("transient") : cause);
        lastTransientCause = null;
    }

    /** The breaker just opened: pause all the group's partitions - the broker keeps delivering, the base
     * consumes none of it until the half-open trial (T-191, E8-F1-S3). */
    private void breakerOpened() {
        breakerPausedAll = true;
        Set<TopicPartition> groupPartitions = new LinkedHashSet<>(consumer.assignment());
        groupPartitions.addAll(paused.keySet());
        if (!groupPartitions.isEmpty()) {
            consumer.pause(groupPartitions);
        }
        countPause("breaker");
    }

    private void countPause(String reason) {
        if (meters != null) {
            meters.counter("platform.stage.pauses", "group", settings.group(), "reason", reason).increment();
        }
    }

    private void countOutcome(Outcome outcome, String topic) {
        if (meters != null) {
            meters.counter("platform.stage.records", "group", settings.group(), "topic", topic,
                    "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
        }
    }

    private void countPoisonArrival(ConsumerRecord<byte[], byte[]> record) {
        if (meters != null) {
            meters.counter("platform.stage.poison.arrivals", "group", settings.group(), "topic", record.topic())
                    .increment();
            meters.counter("platform.alerts.fired", "alert", "poison").increment();
        }
        if (alerts != null) {
            alerts.poisonArrived(settings.group(), record.topic(), record.partition(), record.offset(),
                    lastUnreadableReason);
        }
        lastUnreadableReason = null;
    }

    /** One span per record; the receiver restores the record's {@code traceparent} header into the span. */
    private io.micrometer.observation.Observation recordObservation(ConsumerRecord<byte[], byte[]> record) {
        if (observations == null || io.micrometer.observation.ObservationRegistry.NOOP.equals(observations)) {
            return io.micrometer.observation.Observation.NOOP;
        }
        java.util.Map<String, String> carrier = new HashMap<>();
        for (org.apache.kafka.common.header.Header header : record.headers()) {
            if ("traceparent".equals(header.key()) && header.value() != null) {
                carrier.put("traceparent", new String(header.value(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        io.micrometer.observation.transport.ReceiverContext<java.util.Map<String, String>> context =
                new io.micrometer.observation.transport.ReceiverContext<>((got, key) -> got.get(key));
        context.setCarrier(carrier);
        return io.micrometer.observation.Observation.createNotStarted(RECORD_SPAN, () -> context, observations);
    }

    /** Drains until {@code deadline}: poll turns with their pauses and back-offs, until no time is left. */
    public void runUntil(Instant deadline) throws InterruptedException {
        while (clock.instant().isBefore(deadline)) {
            drainOnce();
        }
    }

    private Outcome noRow(WorkKind kind, WorkRecord contract) {
        // E8-F1-S1, backlog.md:2051-2054: nothing is processed and the event is logged.
        org.slf4j.LoggerFactory.getLogger(StageConsumer.class)
                .info("stage consumer: no row for work kind={}, id={}, record ignored", kind.name(), contract.id());
        return Outcome.NO_ROW;
    }
}