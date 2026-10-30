package io.integrationplatform.routing.topics;

/**
 * The Kafka work topics and the one poison topic, by name (ADR-010:94-95). The work-topic strings are the
 * same values {@code io.integrationplatform.ingest.port.OutboxTopic} stores on {@code outbox.topic} - the
 * data plane reads the stored name from the record it consumes, so these constants exist for the stage
 * consumers' subscriptions, the poison producer, the record-key helper and the E8 test harness, which
 * creates exactly these three topics. R12: plain strings here - no Kafka client class is imported.
 */
public final class Topics {

    /** Work topic - a message was accepted by the intake transaction ({@code platform.message.accepted}). */
    public static final String MESSAGE_ACCEPTED = "platform.message.accepted";

    /** Work topic - a delivery became {@code DUE}: stage 1, release, re-drive or replay ({@code platform.delivery.due}). */
    public static final String DELIVERY_DUE = "platform.delivery.due";

    /** Work topic - a callback-configured delivery captured a response; the callback engine picks it up. */
    public static final String CALLBACK_DUE = "platform.callback.due";

    /**
     * The one poison topic ({@code platform.poison}): an unreadable record is written here once, the partition
     * moves on, and nothing is ever retried from there - Kafka carries work only, no retry or delayed-retry
     * topics exist (E8-C6, E8-C7).
     */
    public static final String POISON = "platform.poison";

    /**
     * Partitions per work topic (ADR-005 Decision 6; T181). It is also the ceiling for the stage consumer loop count
     * ({@code platform.routing.stage1.concurrency}, {@code ...stage2.concurrency}): a loop beyond the partition count
     * would sit idle. Nothing in the application creates the topics (auto-create is off), so whoever creates them -
     * the E18 harness, a deployment - uses this figure.
     */
    public static final int WORK_TOPIC_PARTITIONS = 12;

    private Topics() {
    }
}