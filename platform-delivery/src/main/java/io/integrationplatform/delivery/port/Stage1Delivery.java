package io.integrationplatform.delivery.port;

import java.util.UUID;

/**
 * One stage-1 delivery to create (E9 seam, arjun-fullstack, F14): everything {@link Stage1Deliveries#insertPerRoute}
 * needs for one route. An immutable value - the routing consumer builds these after the rule conditions select the
 * routes (T-117), each pinning that target's contract and mapping version (E9-C3).
 *
 * @param messageId        the routed message's id
 * @param routeId          the route (published interface-to-target binding) this delivery goes to
 * @param acceptedAt       the message's {@code accepted_at}, copied from the message row - becomes the delivery's
 *                         {@code created_at} (ADR-014; never a fresh timestamp)
 * @param targetEndpointId the pinned target endpoint
 * @param mappingVersionId the pinned mapping version (later configuration changes never alter it, E9-F1-S1)
 * @param contractVersionId the pinned target contract version of the same mapping (the snapshot the message was
 *                         accepted under); written beside the mapping version so an operator replay/retry is pinned
 *                         to both. {@code null} only when the route's mapping is unbound
 * @param orderingKey      the interface's ordering key value for this message (hold/scope key), or {@code null}
 *                         when the interface's ordering is {@code NONE}
 * @param interfaceId      the message's {@code interface_id} - with {@code businessKey} and the target, the ordering
 *                         scope the due-claim and the best-effort hold compare (ADR-011 Decision 6)
 * @param businessKey      the message's {@code business_key}
 * @param keySeq           the message's per-key {@code key_seq}, or {@code null} when the message has none
 */
public record Stage1Delivery(UUID messageId, UUID routeId, java.time.Instant acceptedAt, UUID targetEndpointId,
        UUID mappingVersionId, UUID contractVersionId, String orderingKey, UUID interfaceId, String businessKey, Long keySeq) {
}