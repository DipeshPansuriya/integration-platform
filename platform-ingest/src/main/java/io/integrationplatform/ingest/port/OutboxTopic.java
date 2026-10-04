package io.integrationplatform.ingest.port;

/**
 * The work topics an outbox row is published to (ADR-005); the stored value is {@link #topicName()}, held by the
 * {@code CHECK} on {@code outbox.topic}.
 */
public enum OutboxTopic {

    /** A message was accepted by the intake transaction (architecture.md 4.1 step 6). */
    MESSAGE_ACCEPTED("platform.message.accepted"),

    /** A delivery became {@code DUE} (stage 1, release, re-drive or replay; architecture.md 4.1 steps 8 and 13). */
    DELIVERY_DUE("platform.delivery.due");

    private final String topicName;

    OutboxTopic(String topicName) {
        this.topicName = topicName;
    }

    /** The topic name, as stored in {@code outbox.topic} and used by the publish adapter. */
    public String topicName() {
        return topicName;
    }
}
