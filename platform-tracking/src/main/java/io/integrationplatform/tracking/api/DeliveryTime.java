package io.integrationplatform.tracking.api;

import io.integrationplatform.domain.delivery.DeliveryState;

/**
 * E13 seam (arjun-fullstack): one delivery of a message - its target and the last attempt's sent-to-answered time and
 * outcome (null when none). {@code state} is the delivery's own state and is never null.
 */
public record DeliveryTime(String deliveryId, String targetId, Long durationMs, String outcome, DeliveryState state) {
}
