package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * The stage consumer base decisions (T-111, T-113, T-181) against a fetch-model broker fake - every E8-C rule the
 * container tests prove against a real broker is decided here first: the row decides the tenant (E8-C4), a
 * missing row does nothing, an unreadable record goes to poison once and its partition moves on (T-113), and a
 * transient database error pauses the partition, never commits the offset, retries the same record after a
 * back-off and resumes - no record skipped, none poisoned (T-181, ADR fix m-817272 B4).
 */
class StageConsumerTest {

    private static final String TOPIC = io.integrationplatform.routing.topics.Topics.MESSAGE_ACCEPTED;
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final UUID ROW_ID = UUID.fromString("33333333-0000-4000-8000-000000000003");
    /** The tenant the protected row carries - never the tenant any record claims. */
    private static final UUID ROW_TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID FORGED_TENANT = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private final List<Duration> sleeps = new ArrayList<>();
    private final List<byte[]> poisonedValues = new ArrayList<>();
    private final List<UUID> workDone = new ArrayList<>();
    private final SettableClock clock = new SettableClock();
    private final DecidingRows rows = new DecidingRows();

    /**
     * A fetch-model fake: records stay buffered per partition and a poll returns what the fetch position allows -
     * so a seek back to a failed record's offset (the base's transient rule) really redelivers it on the next
     * poll, exactly as a broker does.
     */
    private final Consumer<byte[], byte[]> broker = new RedeliverConsumer();

    private final RedeliverConsumer redeliverFake = (RedeliverConsumer) broker;

    private final StageConsumer stage = new StageConsumer(broker, new WorkRecordParser(), rows,
            (record, tenantId) -> workDone.add(tenantId),
            (key, value, reason) -> poisonedValues.add(value),
            attempt -> Duration.ofMillis(250L * attempt), Duration.ofMillis(50), clock,
            duration -> {
                clock.advance(duration);
                sleeps.add(duration);
            });

    private ConsumerRecord<byte[], byte[]> workRecordAt(long offset) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, offset, new byte[0],
                ("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + ROW_ID + "\"}").getBytes());
        record.headers().add("traceparent",
                "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01".getBytes());
        return record;
    }

    @Test
    void aConsumedRecordWorksAsTheRowTenantAndCommitsItsOffset() throws Exception {
        redeliverFake.seed(PARTITION, workRecordAt(0));

        stage.runUntil(clock.instant().plusSeconds(5));

        assertThat(workDone).as("the consumer bound the tenant of the protected row")
                .containsExactly(ROW_TENANT);
        assertThat(redeliverFake.commitLog()).containsExactly(1L);
        assertThat(redeliverFake.pausedPartitions())
                .as("nothing paused when the work went through").isEmpty();
        assertThat(poisonedValues).isEmpty();
    }

    @Test
    void aForgedTenantOnTheRecordIsIgnoredTheRowDecides() throws Exception {
        ConsumerRecord<byte[], byte[]> forged = workRecordAt(0);
        forged.headers().add("tenant_id", FORGED_TENANT.toString().getBytes());
        forged.headers().add("payload", "{\"amount\":100}".getBytes());
        redeliverFake.seed(PARTITION, forged);

        stage.runUntil(clock.instant().plusSeconds(5));

        assertThat(workDone).as("the consumer worked as tenant A, never the forged tenant B")
                .containsExactly(ROW_TENANT);
        assertThat(redeliverFake.commitLog()).containsExactly(1L);
    }

    @Test
    void aRecordForAMissingRowDoesNothing() throws Exception {
        StageConsumer.ProtectedRows missing = new StageConsumer.ProtectedRows() {
            @Override
            public UUID tenantFor(WorkKind kind, UUID id) {
                return null;
            }

            @Override
            public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
                throw new AssertionError("nothing is loaded for a missing row");
            }
        };
        List<UUID> handled = new ArrayList<>();
        List<byte[]> poisoned = new ArrayList<>();
        StageConsumer consumerOfMissingRow = new StageConsumer(broker, new WorkRecordParser(), missing,
                (record, tenantId) -> handled.add(tenantId), (key, value, reason) -> poisoned.add(value),
                attempt -> Duration.ofMillis(250L * attempt), Duration.ofMillis(50), clock,
                duration -> clock.advance(duration));

        StageConsumer.Outcome outcome = consumerOfMissingRow.handleOne(workRecordAt(0));

        assertThat(outcome).isEqualTo(StageConsumer.Outcome.NO_ROW);
        assertThat(handled).as("nothing is processed for a missing row").isEmpty();
        assertThat(poisoned).as("a missing row is not unreadable - it is not poisoned").isEmpty();
    }

    @Test
    void anUnreadableRecordGoesToPoisonOnceAndThePartitionMovesOn() throws Exception {
        ConsumerRecord<byte[], byte[]> unreadable = new ConsumerRecord<>(TOPIC, 0, 0, new byte[0],
                "not a record contract at all".getBytes());
        redeliverFake.seed(PARTITION, unreadable, workRecordAt(1));

        stage.runUntil(clock.instant().plusSeconds(5));

        assertThat(poisonedValues).as("exactly one poison write - never a retry path").hasSize(1);
        assertThat(poisonedValues.get(0)).isEqualTo("not a record contract at all".getBytes());
        assertThat(workDone).as("the partition moved on to the next record").hasSize(1);
        assertThat(redeliverFake.commitLog()).as("both records' offsets committed, in order").containsExactly(1L, 2L);
    }

    @Test
    void aTransientDatabaseErrorPausesThePartitionRetriesTheSameRecordAfterBackOffAndResumes() throws Exception {
        rows.failTheNextVisibleChecks(2);
        redeliverFake.seed(PARTITION, workRecordAt(0));

        stage.runUntil(clock.instant().plusSeconds(30));

        assertThat(redeliverFake.commitLog()).as("the record's offset is committed only when its work succeeded, then once")
                .containsExactly(1L);
        assertThat(redeliverFake.pausedPartitions()).as("the partition resumed when the retry succeeded").isEmpty();
        assertThat(sleeps).as("the retry waited a positive back-off before each attempt")
                .allSatisfy(wait -> assertThat(wait).isPositive());
        assertThat(sleeps.stream().mapToLong(Duration::toMillis).sum())
                .as("the two failed attempts' back-offs (250 ms + 500 ms) were actually waited in full")
                .isGreaterThanOrEqualTo(750L);
        assertThat(workDone).as("no record is skipped - handled exactly once, after the resume").hasSize(1);
        assertThat(poisonedValues).as("a transient database error never goes to the poison topic").isEmpty();
    }

    @Test
    void aTransientErrorNeverSkipsTheRecordBehindIt() throws Exception {
        rows.failTheNextVisibleChecks(1);
        redeliverFake.seed(PARTITION, workRecordAt(0), workRecordAt(1));

        stage.runUntil(clock.instant().plusSeconds(30));

        assertThat(redeliverFake.commitLog()).as("both offsets committed in acceptance order after the retry").containsExactly(1L, 2L);
        assertThat(workDone).as("both records delivered, none skipped, none poisoned").hasSize(2);
        assertThat(poisonedValues).isEmpty();
    }

    // ---------- T-191 (E8-F1-S3; ADR-005 Decision 12): the five controls on the stage consumers ----------

    private static final String GROUP = "platform-stage-1";
    private static final TopicPartition SECOND_PARTITION = new TopicPartition(TOPIC, 1);
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> alerts = new ArrayList<>();
    private final StageAlerts recordingAlerts = new StageAlerts() {
        @Override
        public void poisonArrived(String group, String topic, int partition, long offset, String reason) {
            alerts.add("poison " + group + " " + topic + "-" + partition + "@" + offset);
        }

        @Override
        public void lagExceeded(LagAlert alert) {
            alerts.add("lag " + alert.group());
        }
    };

    private StageConsumer controlledStage(ObservationRegistry observations) {
        return new StageConsumer(broker, new WorkRecordParser(), rows, (record, tenantId) -> workDone.add(tenantId),
                (key, value, reason) -> poisonedValues.add(value), attempt -> Duration.ofMillis(250L * attempt),
                Duration.ofMillis(50), clock, duration -> {
                    clock.advance(duration);
                    sleeps.add(duration);
                }, new StageSettings(GROUP, 5, Duration.ofSeconds(30)), meters, observations, recordingAlerts);
    }

    private ConsumerRecord<byte[], byte[]> workRecordAt(int partition, long offset) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, partition, offset, new byte[0],
                ("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + ROW_ID + "\"}").getBytes());
        record.headers().add("traceparent", TRACEPARENT.getBytes());
        return record;
    }

    @Test
    void fiveConsecutiveTransientErrorsOpenTheGroupsDatabaseBreakerAndPauseEveryPartitionForTheOpenPeriod()
            throws Exception {
        redeliverFake.assign(List.of(PARTITION, SECOND_PARTITION));
        redeliverFake.seed(PARTITION, workRecordAt(0, 0));
        redeliverFake.seed(SECOND_PARTITION, workRecordAt(1, 0));
        rows.failTheNextVisibleChecks(5); // the database is down for exactly five calls
        StageConsumer stage = controlledStage(ObservationRegistry.NOOP);

        for (int turn = 0; turn < 20 && !breakerOpen(); turn++) {
            stage.drainOnce();
        }
        assertThat(breakerOpen()).as("5 consecutive transient database errors open the group's breaker").isTrue();
        assertThat(redeliverFake.pauseCalls())
                .as("the open breaker pauses ALL the group's partitions, not only the failing one")
                .anySatisfy(paused -> assertThat(paused).contains(PARTITION, SECOND_PARTITION));

        stage.drainOnce();
        stage.drainOnce();
        assertThat(workDone).as("inside the open period nothing is consumed, though the database is back")
                .isEmpty();
        assertThat(meters.get("platform.stage.pauses").tags("group", GROUP, "reason", "breaker").counter().count())
                .isGreaterThanOrEqualTo(1.0);

        stage.runUntil(clock.instant().plusSeconds(120));

        assertThat(workDone).as("after the open period both records are consumed - none skipped, none poisoned")
                .hasSize(2);
        assertThat(redeliverFake.pausedPartitions()).as("every partition resumed").isEmpty();
        assertThat(poisonedValues).isEmpty();
    }

    @Test
    void everyConsumedRecordIsObservableOutcomesPausesPoisonArrivalsAndASpanRestoringTheTraceparent()
            throws Exception {
        ConsumerRecord<byte[], byte[]> unreadable = new ConsumerRecord<>(TOPIC, 0, 0, new byte[0],
                "not a record contract at all".getBytes());
        redeliverFake.seed(PARTITION, unreadable, workRecordAt(0, 1));
        rows.failTheNextVisibleChecks(1);
        ObservationRegistry observations = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        StageConsumer stage = controlledStage(observations);

        stage.runUntil(clock.instant().plusSeconds(10));

        assertThat(outcomes("poisoned")).as("records per outcome").isEqualTo(1.0);
        assertThat(outcomes("transient")).isEqualTo(1.0);
        assertThat(outcomes("consumed")).isEqualTo(1.0);
        assertThat(meters.get("platform.stage.pauses").tags("group", GROUP, "reason", "transient").counter()
                .count()).as("pauses").isEqualTo(1.0);
        assertThat(meters.get("platform.stage.poison.arrivals").tags("group", GROUP, "topic", TOPIC).counter()
                .count()).as("poison arrivals").isEqualTo(1.0);
        assertThat(alerts).as("every poison arrival raises the alert, naming group, topic, partition, offset")
                .containsExactly("poison " + GROUP + " " + TOPIC + "-0@0");
        assertThat(meters.get("platform.alerts.fired").tags("alert", "poison").counter().count()).isEqualTo(1.0);
        assertThat(stopped).extracting(Observation.Context::getName).as("a finished span per record")
                .containsOnly("platform.stage.record").hasSize(3);
        List<String> restored = stopped.stream()
                .filter(observation -> observation instanceof ReceiverContext<?>)
                .map(observation -> restoredTraceparent((ReceiverContext<?>) observation)).toList();
        assertThat(restored).as("a span per record, restoring the record's traceparent header").hasSize(3)
                .contains(TRACEPARENT);
    }

    @Test
    void theConsumerSettingsCarryTheTimeoutControls() {
        Map<String, Object> config = StageConsumer.consumerConfig("broker:9092", GROUP);

        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, GROUP)
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .as("ADR-005 D12: max.poll.interval.ms 5 minutes - no target call runs on the poll thread")
                .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000)
                .as("every blocking client call (commit, position) is bounded")
                .containsEntry(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000)
                .containsEntry(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
    }

    private boolean breakerOpen() {
        return meters.find("resilience4j.circuitbreaker.state").tags("name", "database-" + GROUP, "state", "open")
                .gauge() != null
                && meters.get("resilience4j.circuitbreaker.state").tags("name", "database-" + GROUP, "state", "open")
                        .gauge().value() == 1.0;
    }

    private double outcomes(String outcome) {
        return meters.get("platform.stage.records").tags("group", GROUP, "topic", TOPIC, "outcome", outcome)
                .counter().count();
    }

    private static String restoredTraceparent(ReceiverContext<?> context) {
        @SuppressWarnings("unchecked")
        ReceiverContext<Object> receiver = (ReceiverContext<Object>) context;
        return receiver.getGetter().get(receiver.getCarrier(), "traceparent");
    }

    /** The protected-row fake: the row always carries {@link #ROW_TENANT}, its visibility can fail transiently. */
    private static final class DecidingRows implements StageConsumer.ProtectedRows {

        private int visibleFailuresLeft;

        void failTheNextVisibleChecks(int times) {
            visibleFailuresLeft = times;
        }

        @Override
        public UUID tenantFor(WorkKind kind, UUID id) {
            return ROW_TENANT;
        }

        @Override
        public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
            if (visibleFailuresLeft > 0) {
                visibleFailuresLeft--;
                throw new TransientWorkException("database call failed transiently");
            }
            return true;
        }
    }

    /** A {@link Clock} the tests advance through the fake sleeper - retries never wall-clock-wait. */
    static final class SettableClock extends Clock {

        private Instant now = Instant.parse("2026-10-13T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Etc/UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    /** A broker fake whose fetch semantics the base's seek-back rule can be proven against. */
    private static final class RedeliverConsumer extends MockConsumer<byte[], byte[]> {

        private final Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> buffered = new LinkedHashMap<>();
        private final Map<TopicPartition, Long> position = new HashMap<>();
        private final List<Long> commitLog = new ArrayList<>();
        private final Set<TopicPartition> paused = new HashSet<>();
        private final List<Set<TopicPartition>> pauseCalls = new ArrayList<>();

        RedeliverConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        void seed(TopicPartition partition, ConsumerRecord<byte[], byte[]>... records) {
            buffered.put(partition, new ArrayList<>(List.of(records)));
            position.put(partition, records[0].offset());
        }

        long fetchPositionAt(TopicPartition partition) {
            return position.getOrDefault(partition, 0L);
        }

        List<Long> commitLog() {
            return commitLog;
        }

        Set<TopicPartition> pausedPartitions() {
            return paused;
        }

        /** Every pause call's partitions, in call order. */
        List<Set<TopicPartition>> pauseCalls() {
            return pauseCalls;
        }

        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> batch = new HashMap<>();
            for (Map.Entry<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> partition : buffered.entrySet()) {
                if (paused.contains(partition.getKey())) {
                    continue;
                }
                List<ConsumerRecord<byte[], byte[]>> fetch = partition.getValue().stream()
                        .filter(record -> record.offset() >= position.getOrDefault(partition.getKey(), 0L)).toList();
                if (!fetch.isEmpty()) {
                    batch.put(partition.getKey(), fetch);
                    position.put(partition.getKey(), fetch.get(fetch.size() - 1).offset() + 1);
                }
            }
            return batch.isEmpty() ? ConsumerRecords.empty() : new ConsumerRecords<>(batch);
        }

        @Override
        public synchronized void seek(TopicPartition partition, long offset) {
            position.put(partition, offset);
        }

        @Override
        public synchronized void pause(Collection<TopicPartition> partitions) {
            pauseCalls.add(Set.copyOf(partitions));
            paused.addAll(partitions);
        }

        @Override
        public synchronized void resume(Collection<TopicPartition> partitions) {
            paused.removeAll(partitions);
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            OffsetAndMetadata offset = offsets.get(PARTITION);
            if (offset != null) {
                commitLog.add(offset.offset());
            }
        }
    }
}