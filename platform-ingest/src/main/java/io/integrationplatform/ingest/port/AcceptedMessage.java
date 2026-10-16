package io.integrationplatform.ingest.port;

import java.util.UUID;

/**
 * What a successful intake answered (E11 A1 narrow): the tracking id the API returns and the outbox row the
 * commit produced. Decision (lane A, 2026-10-13): the tracking id <b>is</b> the message id - it flows through the
 * outbox record ({@code aggregate_id}) and the relay to every later stage, so the id is observable end to end with
 * no schema change; if the API ever needs a different public id, this record is the one place that changes.
 *
 * @param trackingId the message id; the message's {@code accepted_at} (its partition key) belongs to the same
 *        committed transaction
 * @param outboxId the outbox row id carrying {@code platform.message.accepted} for this message
 */
public record AcceptedMessage(UUID trackingId, long outboxId) {

    public AcceptedMessage {
        java.util.Objects.requireNonNull(trackingId, "trackingId");
    }
}