package io.integrationplatform.routing.publish;

import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.ingest.port.PublishReceipt;
import java.time.Duration;

/**
 * RED state of T-110/T-112 (E8-F1-S1/S2): the adapter exists so the written tests compile, but none of
 * the broker behaviour is built yet - the publish sends nothing, the acknowledge answers nothing. The
 * real record contract (stored key, ids-only value, traceparent header, fire-and-forget + acknowledge)
 * arrives in the green commit.
 */
public final class KafkaPublishPort implements PublishPort {

    public KafkaPublishPort(org.apache.kafka.clients.producer.Producer<String, String> producer) {
    }

    @Override
    public PublishReceipt publish(OutboundRecord record) {
        throw new UnsupportedOperationException("red (T-110): the Kafka publish adapter is not implemented yet");
    }

    @Override
    public boolean acknowledge(PublishReceipt receipt, Duration timeout) {
        throw new UnsupportedOperationException("red (T-110): the Kafka publish adapter is not implemented yet");
    }
}