package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.routing.topics.Topics;
import org.junit.jupiter.api.Test;

/** T181: the loop count is a setting, defaults to 1 and never exceeds the partition count of the work topics. */
class StageConcurrencyTest {

    @Test
    void theWorkTopicsHaveTwelvePartitions() {
        assertThat(Topics.WORK_TOPIC_PARTITIONS).isEqualTo(12);
    }

    @Test
    void oneLoopKeepsTodaysThreadName() {
        assertThat(StageConsumerLoop.threadName("platform-stage1", 1, 1)).isEqualTo("stage-platform-stage1");
    }

    @Test
    void severalLoopsGetOneNumberedThreadEach() {
        assertThat(StageConsumerLoop.threadName("platform-stage1", 2, 5)).isEqualTo("stage-platform-stage1-2");
    }

    @Test
    void theCountMustBeBetweenOneAndThePartitionCount() {
        assertThat(StageConsumerLoop.checkedConcurrency("platform-stage1", 1)).isEqualTo(1);
        assertThat(StageConsumerLoop.checkedConcurrency("platform-stage1", 12)).isEqualTo(12);
        assertThatThrownBy(() -> StageConsumerLoop.checkedConcurrency("platform-stage1", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("platform-stage1");
        assertThatThrownBy(() -> StageConsumerLoop.checkedConcurrency("platform-stage1", 13))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12");
    }
}
