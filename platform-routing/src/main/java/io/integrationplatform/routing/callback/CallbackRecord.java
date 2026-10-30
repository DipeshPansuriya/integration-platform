package io.integrationplatform.routing.callback;

import java.util.UUID;

/**
 * One pending callback: the delivery's captured response and the route's callback destination.
 *
 * @param responseId   the delivery_response row id
 * @param tenantId     the owning tenant
 * @param deliveryId   the delivery whose response was captured
 * @param callbackUrl  the route's callback URL
 * @param callbackMode IMMEDIATE or SCHEDULED
 * @param status       the HTTP status the target returned
 * @param contentType  the Content-Type of the response body (nullable)
 * @param body         the response body bytes (nullable)
 * @param attempts     how many callback attempts have been made
 */
public record CallbackRecord(UUID responseId, UUID tenantId, UUID deliveryId, String callbackUrl,
        String callbackMode, int status, String contentType, byte[] body, int attempts) {
}
