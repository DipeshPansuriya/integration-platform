package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.routing.topics.Topics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** T181: N consumer loops per stage in one consumer group - gauge names stay unique, order stays per partition. */
class NConsumerLoopsTest {

    private static final String GROUP = "platform-stage1";
    private static final String TOPIC = Topics.MESSAGE_ACCEPTED;
    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    @Test
    void oneLoopKeepsTodaysBreakerNameSoNothingChangesUntilConfigured() {
        assertThat(StageSettings.forLoop(GROUP, 1, 1).breakerName()).isEqualTo("database-" + GROUP);
        assertThat(StageSettings.defaults(GROUP).breakerName()).isEqualTo("database-" + GROUP);
    }

    @Test
    void everyLoopOfAGroupRegistersItsOwnBreakerGauges() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        int loops = 4;
        for (int index = 1; index <= loops; index++) {
            stage(index, loops, meters, new MockConsumer<>(OffsetResetStrategy.EARLIEST), new ArrayList<>());
        }
        Set<String> names = new HashSet<>();
        meters.find(StageConsumer.BREAKER_STATE_GAUGE).gauges().forEach(g -> names.add(g.getId().getTag("name")));
        assertThat(names).as("one breaker name per loop, none shared").hasSize(loops)
                .allSatisfy(name -> assertThat(name).startsWith("database-" + GROUP + "-"));
        assertThat(meters.find(StageConsumer.BREAKER_STATE_GAUGE).gauges())
                .as("every state per loop breaker")
                .hasSize(loops * io.github.resilience4j.circuitbreaker.CircuitBreaker.State.values().length);
        assertThat(meters.find(StageConsumer.BREAKER_STATE_GAUGE).tag("state", "closed").gauges())
                .as("each loop reports its own closed state").hasSize(loops).extracting(Gauge::value)
                .containsOnly(1.0d);
    }

    @Test
    void aRecordIsHandledByTheLoopThatOwnsItsPartitionAndInOffsetOrder() throws Exception {
        List<String> handledByLoop1 = new ArrayList<>();
        List<String> handledByLoop2 = new ArrayList<>();
        MockConsumer<byte[], byte[]> consumer1 = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        MockConsumer<byte[], byte[]> consumer2 = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition p0 = new TopicPartition(TOPIC, 0);
        TopicPartition p1 = new TopicPartition(TOPIC, 1);
        consumer1.assign(List.of(p0));
        consumer2.assign(List.of(p1));
        consumer1.updateBeginningOffsets(Map.of(p0, 0L));
        consumer2.updateBeginningOffsets(Map.of(p1, 0L));
        for (int offset = 0; offset < 5; offset++) {
            consumer1.addRecord(record(0, offset));
            consumer2.addRecord(record(1, offset));
        }
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StageConsumer loop1 = stage(1, 2, meters, consumer1, handledByLoop1);
        StageConsumer loop2 = stage(2, 2, meters, consumer2, handledByLoop2);
        for (int turn = 0; turn < 3; turn++) {
            loop1.drainOnce();
            loop2.drainOnce();
        }
        assertThat(handledByLoop1).containsExactly("0@0", "0@1", "0@2", "0@3", "0@4");
        assertThat(handledByLoop2).containsExactly("1@0", "1@1", "1@2", "1@3", "1@4");
        assertThat(consumer1.committed(Set.of(p0)).get(p0).offset()).isEqualTo(5L);
        assertThat(consumer2.committed(Set.of(p1)).get(p1).offset()).isEqualTo(5L);
    }

    @Test
    void theTenantComesFromTheRowOnEveryLoopNeverFromTheRecord() throws Exception {
        Map<String, UUID> seen = new ConcurrentHashMap<>();
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition p3 = new TopicPartition(TOPIC, 3);
        consumer.assign(List.of(p3));
        consumer.updateBeginningOffsets(Map.of(p3, 0L));
        consumer.addRecord(record(3, 0));
        StageConsumer loop = new StageConsumer(consumer, new WorkRecordParser(), rows(),
                (record, tenantId) -> seen.put("loop3", tenantId), (key, value, reason) -> { },
                attempt -> Duration.ofMillis(10), Duration.ofMillis(10), Clock.systemUTC(), d -> { },
                StageSettings.forLoop(GROUP, 3, 4), new SimpleMeterRegistry(), ObservationRegistry.NOOP,
                StageAlerts.LOG);
        loop.drainOnce();
        assertThat(seen).containsEntry("loop3", TENANT);
    }

    @Test
    void settingsRejectALoopIndexOutsideTheLoopCount() {
        assertThatThrownBy(() -> StageSettings.forLoop(GROUP, 0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StageSettings.forLoop(GROUP, 3, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static StageConsumer stage(int index, int loops, SimpleMeterRegistry meters,
            MockConsumer<byte[], byte[]> consumer, List<String> handled) {
        return new StageConsumer(consumer, new WorkRecordParser(), rows(),
                (record, tenantId) -> handled.add(record.id().getMostSignificantBits() + "@" + record.id().getLeastSignificantBits()),
                (key, value, reason) -> { }, attempt -> Duration.ofMillis(10), Duration.ofMillis(10),
                Clock.systemUTC(), d -> { }, StageSettings.forLoop(GROUP, index, loops), meters,
                ObservationRegistry.NOOP, StageAlerts.LOG);
    }

    private static StageConsumer.ProtectedRows rows() {
        return new StageConsumer.ProtectedRows() {
            @Override
            public UUID tenantFor(WorkKind kind, UUID id) {
                return TENANT;
            }

            @Override
            public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
                return true;
            }
        };
    }

    private static ConsumerRecord<byte[], byte[]> record(int partition, long offset) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, partition, offset, new byte[0],
                ("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + new UUID(partition, offset) + "\"}").getBytes());
        record.headers().add("traceparent", "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01".getBytes());
        return record;
    }
}
