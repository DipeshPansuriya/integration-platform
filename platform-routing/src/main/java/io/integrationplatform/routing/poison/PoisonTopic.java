package io.integrationplatform.routing.poison;

import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.topics.Topics;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * The one poison topic producer (T-113, E8-F1-S2): an unreadable record is written to {@link Topics#POISON}
 * exactly once - the partition moves on and nothing is ever retried from there, so poison is never a retry path
 * (E8-C6, E8-C7). The raw record bytes are kept as they were, so support can read what was unreadable.
 *
 * <p>A failed poison write is raised as {@link IllegalStateException}: the stage base then pauses the partition
 * instead of committing the record's offset, so the record is never lost to a failed poison write.
 */
public final class PoisonTopic implements StageConsumer.PoisonSink {

    private final Producer<byte[], byte[]> producer;
    private final long poisonWriteTimeoutSeconds;

    public PoisonTopic(Producer<byte[], byte[]> producer, long poisonWriteTimeoutSeconds) {
        this.producer = producer;
        this.poisonWriteTimeoutSeconds = poisonWriteTimeoutSeconds;
    }

    /** The default poison-write wait of 5 seconds. */
    public PoisonTopic(Producer<byte[], byte[]> producer) {
        this(producer, 5);
    }

    @Override
    public void write(byte[] key, byte[] value, String reason) {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(Topics.POISON, key, value);
        try {
            producer.send(record).get(poisonWriteTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("poison write interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("poison write failed: " + e.getMessage(), e);
        }
    }
}