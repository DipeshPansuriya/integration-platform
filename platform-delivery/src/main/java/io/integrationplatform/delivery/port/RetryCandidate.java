package io.integrationplatform.delivery.port;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery in {@code RETRY_WAIT} whose retry time has passed, locked by {@link DeliveryRetryStore#lockDue} in the
 * caller's transaction.
 *
 * @param deliveryId    the delivery
 * @param attempts      attempts recorded so far in {@code delivery_attempt} (layer 2 count, ADR-010 section 4)
 * @param nextAttemptAt the stored retry time that has passed
 */
public record RetryCandidate(UUID deliveryId, int attempts, Instant nextAttemptAt) {
}
