package io.integrationplatform.routing.stage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;

/**
 * The consumer-lag check (T-191, E8-F1-S3; ADR-005 Decision 12): reads a group's committed offsets and the
 * partitions' log offsets and timestamps through the admin client only - it works the same when the consumer
 * is stuck or gone. A group more than {@code maxLagRecords} behind, or whose oldest unconsumed record is older
 * than {@code maxLagAge}, fires {@code platform.alerts.fired{alert=consumer_lag}}, raises the alert on
 * {@link StageAlerts} and returns it; the per-partition lag feeds {@code platform.stage.lag.records}.
 *
 * <p>The age of the oldest unconsumed record is measured from the log's first record timestamp (the admin
 * client answers the log-boundary timestamps only, {@code ponytail}): exact for a partition whose log start
 * still is the group's next record, and an overstatement - which only errs toward alerting - once retention
 * has dropped records the group already consumed.
 */
public final class ConsumerLagCheck {

    /** Lag per group, topic and partition (the alert rule {@code platform_stage_lag_records} reads it). */
    static final String LAG_GAUGE = "platform.stage.lag.records";

    /** Stale partitions per group (the alert rule {@code platform_stage_lag_stale_partitions} reads it). */
    static final String STALE_GAUGE = "platform.stage.lag.stale.partitions";

    private final Admin admin;
    private final String group;
    private final Collection<String> topics;
    private final long maxLagRecords;
    private final Duration maxLagAge;
    private final Duration callTimeout;
    private final Clock clock;
    private final MeterRegistry meters;
    private final StageAlerts alerts;
    private final Map<TopicPartition, AtomicLong> lagGauges = new ConcurrentHashMap<>();
    private AtomicLong staleGauge;

    public ConsumerLagCheck(Admin admin, String group, Collection<String> topics, long maxLagRecords,
            Duration maxLagAge, Duration callTimeout, Clock clock, MeterRegistry meters, StageAlerts alerts) {
        this.admin = Objects.requireNonNull(admin);
        this.group = Objects.requireNonNull(group);
        this.topics = Objects.requireNonNull(topics);
        this.maxLagAge = Objects.requireNonNull(maxLagAge);
        this.callTimeout = Objects.requireNonNull(callTimeout);
        this.clock = Objects.requireNonNull(clock);
        this.meters = meters;
        this.alerts = alerts == null ? StageAlerts.LOG : alerts;
        if (maxLagRecords < 0) {
            throw new IllegalArgumentException("maxLagRecords must not be negative");
        }
        this.maxLagRecords = maxLagRecords;
    }

    /** One check of the group; the alert, if any, has both been raised and is returned. */
    public Optional<LagAlert> check() {
        Map<TopicPartition, Long> committed = committedOffsets();
        Map<TopicPartition, Long> lag = lagOf(committed);
        List<TopicPartition> lagging = lag.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                .toList();
        long totalLag = lagging.stream().mapToLong(lag::get).sum();
        List<TopicPartition> stale = staleOf(lagging);
        if (meters != null) {
            lag.forEach(this::recordLag);
            setStale(stale.size());
        }
        if (totalLag <= maxLagRecords && stale.isEmpty()) {
            return Optional.empty();
        }
        LagAlert alert = new LagAlert(group, lagging, totalLag, stale);
        alerts.lagExceeded(alert);
        if (meters != null) {
            meters.counter("platform.alerts.fired", "alert", "consumer_lag").increment();
        }
        return Optional.of(alert);
    }

    /** The group's committed offsets; absent entries (and an entirely unknown group) commit from zero. */
    private Map<TopicPartition, Long> committedOffsets() {
        Map<TopicPartition, Long> committed = new HashMap<>();
        try {
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(callTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .forEach((partition, metadata) -> committed.put(partition, metadata.offset()));
        } catch (Exception unknownGroup) {
            // the group has committed nothing yet - every observed partition lags from zero
        }
        return committed;
    }

    /** Records behind per observed partition: log end minus the group's committed offset. */
    private Map<TopicPartition, Long> lagOf(Map<TopicPartition, Long> committed) {
        List<TopicPartition> partitions = partitionsOfTopics();
        Map<TopicPartition, OffsetSpec> specs = new TreeMap<>((a, b) ->
                a.topic().compareTo(b.topic()) == 0 ? Integer.compare(a.partition(), b.partition())
                        : a.topic().compareTo(b.topic()));
        for (TopicPartition partition : partitions) {
            // the log end answers the lag count; the age threshold reads the earliest record timestamps
            specs.put(partition, OffsetSpec.latest());
        }
        Map<TopicPartition, Long> lag = new HashMap<>();
        try {
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> result = admin.listOffsets(specs).all()
                    .get(callTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            for (TopicPartition partition : partitions) {
                lag.put(partition, Math.max(0, result.get(partition).offset()
                        - committed.getOrDefault(partition, 0L)));
            }
        } catch (Exception brokerNotReading) {
            // the broker did not answer in the call timeout: no alert this round - the next check retried it
            return Map.of();
        }
        return lag;
    }

    private List<TopicPartition> partitionsOfTopics() {
        List<TopicPartition> partitions = new ArrayList<>();
        try {
            admin.describeTopics(topics).allTopicNames()
                    .get(callTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .forEach((topic, description) -> {
                        for (int partition = 0; partition < description.partitions().size(); partition++) {
                            partitions.add(new TopicPartition(topic, partition));
                        }
                    });
        } catch (Exception unknownTopic) {
            // the topic was deleted while the group still consumed it - nothing to lag on
        }
        partitions.sort(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
        return partitions;
    }

    /** The lagging partitions whose oldest unconsumed record is older than the age threshold: one
     * timestamp read over exactly those partitions (empty lag → no timestamps requested). {@code
     * forTimestamp(0)} is the first record of the log - the broker answers it from the log itself, where
     * the index-based {@code earliestLocal} spec can return no timestamp for a small or fresh log. */
    private List<TopicPartition> staleOf(List<TopicPartition> lagging) {
        if (lagging.isEmpty()) {
            return List.of();
        }
        List<TopicPartition> stale = new ArrayList<>();
        try {
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> result = admin.listOffsets(lagging.stream()
                            .collect(java.util.stream.Collectors.toMap(p -> p,
                                    p -> OffsetSpec.forTimestamp(0L))))
                    .all().get(callTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            for (TopicPartition partition : lagging) {
                long timestamp = result.get(partition).timestamp();
                if (timestamp < 0) {
                    continue; // no timestamp answer for this partition: only the lag-count threshold applies
                }
                Duration age = Duration.ofMillis(clock.instant().toEpochMilli() - timestamp);
                if (age.compareTo(maxLagAge) >= 0) {
                    stale.add(partition);
                }
            }
        } catch (Exception brokerNotReading) {
            // the broker did not answer: the lag-count threshold still applies this round
        }
        return stale;
    }

    private void recordLag(TopicPartition partition, long lag) {
        AtomicLong holder = lagGauges.get(partition);
        if (holder == null) {
            holder = new AtomicLong();
            Gauge.builder(LAG_GAUGE, holder, AtomicLong::doubleValue)
                    .tag("group", group).tag("topic", partition.topic())
                    .tag("partition", String.valueOf(partition.partition()))
                    .register(meters);
            lagGauges.put(partition, holder);
        }
        holder.set(lag);
    }

    private void setStale(int stalePartitions) {
        if (staleGauge == null) {
            staleGauge = new AtomicLong();
            Gauge.builder(STALE_GAUGE, staleGauge, AtomicLong::doubleValue)
                    .tag("group", group)
                    .register(meters);
        }
        staleGauge.set(stalePartitions);
    }
}