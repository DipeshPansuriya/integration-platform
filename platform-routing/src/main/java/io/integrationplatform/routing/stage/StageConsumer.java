package io.integrationplatform.routing.stage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

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

    public StageConsumer(Consumer<byte[], byte[]> consumer, WorkRecordParser parser, ProtectedRows rows,
            Handler handler, PoisonSink poison, java.util.function.IntFunction<Duration> backoff,
            Duration pollTimeout, Clock clock, Sleeper sleeper) {
        this.consumer = consumer;
        this.parser = parser;
        this.rows = rows;
        this.handler = handler;
        this.poison = poison;
        this.backoff = backoff;
        this.pollTimeout = pollTimeout;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** T-191 red skeleton: the controlled constructor - controls not built yet. */
    public StageConsumer(Consumer<byte[], byte[]> consumer, WorkRecordParser parser, ProtectedRows rows,
            Handler handler, PoisonSink poison, java.util.function.IntFunction<Duration> backoff,
            Duration pollTimeout, Clock clock, Sleeper sleeper, StageSettings settings,
            io.micrometer.core.instrument.MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations, StageAlerts alerts) {
        this(consumer, parser, rows, handler, poison, backoff, pollTimeout, clock, sleeper);
    }

    /** T-191 red skeleton: the consumer settings - not built yet. */
    public static Map<String, Object> consumerConfig(String bootstrapServers, String group) {
        return Map.of();
    }

    /** Handles one record; the outcome decides the offset and the partition rule of the class javadoc. */
    public Outcome handleOne(ConsumerRecord<byte[], byte[]> record) {
        WorkRecord contract;
        try {
            contract = parser.parse(record.value(), record.headers());
        } catch (UnreadableRecordException e) {
            // A failed poison write pauses instead of committing: the record is never lost (T-113).
            try {
                poison.write(record.key(), record.value(), e.getMessage());
            } catch (RuntimeException poisonFailure) {
                return Outcome.TRANSIENT;
            }
            return Outcome.POISONED;
        }
        WorkKind kind = WorkKind.of(contract.type());
        UUID tenantId;
        boolean visible;
        try {
            tenantId = rows.tenantFor(kind, contract.id());
            if (tenantId == null) {
                return noRow(kind, contract);
            }
            visible = rows.visibleTo(tenantId, kind, contract.id());
            if (!visible) {
                return noRow(kind, contract);
            }
            handler.onWork(contract, tenantId);
        } catch (RuntimeException e) {
            return Outcome.TRANSIENT;
        }
        return Outcome.CONSUMED;
    }

    /**
     * One poll-and-process turn: resumes due pauses, polls, processes every partition's records in order,
     * commits each record only after its outcome is final, and pauses with a back-off on the first transient
     * error of a partition. Returns the number of records seen.
     */
    public int drainOnce() throws InterruptedException {
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
        for (TopicPartition partition : records.partitions()) {
            for (ConsumerRecord<byte[], byte[]> record : records.records(partition)) {
                seen++;
                Outcome outcome = handleOne(record);
                if (outcome == Outcome.TRANSIENT) {
                    Pause previous = paused.get(partition);
                    int attempt = previous == null ? 1 : previous.attempts() + 1;
                    paused.put(partition, new Pause(attempt, clock.instant().plus(backoff.apply(attempt))));
                    // The record is retried after the back-off and never skipped: seek back to it, so the fetch
                    // position resets and the broker redelivers it (and any behind it) when the partition resumes.
                    consumer.seek(partition, record.offset());
                    consumer.pause(List.of(partition));
                    break;
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