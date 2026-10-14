package io.integrationplatform.routing.stage;

import java.util.UUID;

/**
 * One Kafka work record as the stage base reads it (ADR-011 Decision 7, backlog.md:2035-2038): the value is
 * {@code {"v","type","id"}} and {@code traceparent} is the only header the base keeps. Nothing else crosses the
 * parse - so a forged tenant in a field or header can never reach a handler (E8-C4: the row decides, T-111).
 *
 * @param v           contract version, fixed at 1
 * @param type        one of the two work types, {@code message.accepted} or {@code delivery.due}
 * @param id          the uuid of the row the work names (a message or a delivery id)
 * @param traceparent the record's one kept header, {@code null} when absent
 */
public record WorkRecord(int v, String type, UUID id, String traceparent) {
}