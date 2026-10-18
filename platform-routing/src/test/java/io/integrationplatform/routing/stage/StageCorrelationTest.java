package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * T-064 (row 278 T107, E12-F1-S1): the route and deliver stages continue the journey the Kafka record carries -
 * the stage consumer opens the record's {@code traceparent} as the journey scope around the handler, so every log
 * line the handler writes carries the correlation id, the row's tenant and the row's id, and every outbox row the
 * handler appends inherits the traceparent (the next Kafka header). Nothing is left on the consumer thread.
 */
class StageCorrelationTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01";
    private static final UUID ROW_ID = UUID.fromString("33333333-0000-4000-8000-000000000003");
    private static final UUID ROW_TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    private final List<String> seen = new ArrayList<>();

    private final StageConsumer stage = new StageConsumer(null, new WorkRecordParser(),
            new StageConsumer.ProtectedRows() {
                @Override
                public UUID tenantFor(WorkKind kind, UUID id) {
                    return ROW_TENANT;
                }

                @Override
                public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
                    return true;
                }
            },
            (record, tenantId) -> {
                seen.add(CorrelationContext.currentTraceparent());
                seen.add(MDC.get(CorrelationContext.CORRELATION_ID));
                seen.add(MDC.get(CorrelationContext.TENANT_ID));
                seen.add(MDC.get(CorrelationContext.MESSAGE_ID));
                seen.add(MDC.get(CorrelationContext.DELIVERY_ID));
            },
            (key, value, reason) -> { }, null, null, null, null);

    private static ConsumerRecord<byte[], byte[]> record(String type, String traceparent) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>("work", 0, 0L, new byte[0],
                ("{\"v\":1,\"type\":\"" + type + "\",\"id\":\"" + ROW_ID + "\"}").getBytes());
        if (traceparent != null) {
            record.headers().add("traceparent", traceparent.getBytes());
        }
        return record;
    }

    @Test
    void theRouteStageRunsInTheRecordsJourneyWithTenantAndMessageIdsOnEveryLogLine() {
        assertThat(stage.handleOne(record("message.accepted", TRACEPARENT))).isEqualTo(StageConsumer.Outcome.CONSUMED);

        assertThat(seen).containsExactly(TRACEPARENT, "4bf92f3577b34da6a3ce929d84e974e6", ROW_TENANT.toString(),
                ROW_ID.toString(), null);
        assertThat(CorrelationContext.currentTraceparent()).as("nothing left on the consumer thread").isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void theDeliverStageRunsInTheRecordsJourneyWithTheDeliveryId() {
        stage.handleOne(record("delivery.due", TRACEPARENT));

        assertThat(seen).containsExactly(TRACEPARENT, "4bf92f3577b34da6a3ce929d84e974e6", ROW_TENANT.toString(),
                null, ROW_ID.toString());
    }

    @Test
    void aRecordWithoutATraceparentStillLogsUnderAJourneyOfItsOwn() {
        stage.handleOne(record("message.accepted", null));

        assertThat(seen.get(1)).isNotNull().isEqualTo(CorrelationContext.correlationIdOf(seen.get(0)));
    }
}
