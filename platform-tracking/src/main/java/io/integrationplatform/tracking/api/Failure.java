package io.integrationplatform.tracking.api;

/**
 * E13 seam (arjun-fullstack): one failed or dead-lettered message - where it failed, the error class and the last error
 * text, with origin PLATFORM or TARGET. Read-only view.
 */
public record Failure(String trackingId, String deliveryId, String flowId, String targetId, String failingStage,
        String errorClass, String lastError, String origin, String failedAt) {
}
