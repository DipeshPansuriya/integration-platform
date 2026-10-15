package io.integrationplatform.routing.stage;

import java.util.List;
import org.apache.kafka.common.TopicPartition;

/**
 * One consumer-lag alert (T-191, E8-F1-S3 "Consumer lag alert"; ADR-005 Decision 12: lag above 1,000 records,
 * or the oldest unconsumed record older than 2 minutes, per group) - it names the group and its partitions.
 *
 * @param group           the consumer group behind
 * @param partitions      every partition of the group with unconsumed records
 * @param totalLag        the group's records behind, summed over its partitions
 * @param stalePartitions the partitions whose oldest unconsumed record is older than the age threshold
 */
public record LagAlert(String group, List<TopicPartition> partitions, long totalLag,
        List<TopicPartition> stalePartitions) {

    public LagAlert {
        partitions = List.copyOf(partitions);
        stalePartitions = List.copyOf(stalePartitions);
    }
}
