package io.integrationplatform.tracking.api;

import java.util.List;

/**
 * E13 seam (arjun-fullstack): one message's journey - every recorded stage in pipeline order, the total from first start
 * to latest end, the failing stage (server-side, never a client hint) and the deliveries. Read-only view.
 */
public record Timeline(String trackingId, String flowId, String state, String acceptedAt, List<StageTime> stages,
        Long totalMs, String failingStage, List<DeliveryTime> deliveries) {
}
