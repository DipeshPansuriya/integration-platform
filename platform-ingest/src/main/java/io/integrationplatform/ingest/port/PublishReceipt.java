package io.integrationplatform.ingest.port;

/**
 * The handle {@link PublishPort#publish} returns for one record; each adapter implements it with its own state (an
 * in-memory flag now, a producer future in E8).
 */
public interface PublishReceipt {

    /** The outbox row the published record was read from. */
    long outboxId();
}
