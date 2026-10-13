package io.integrationplatform.routing.stage;

/**
 * The two ADR-010 work topics and which protected table family each names (ADR-011 Decision 8: the kinds of
 * {@code ipaas_work_tenant(kind, id)}).
 */
public enum WorkKind {

    /** A message row ({@code platform.message.accepted}). */
    MESSAGE,

    /** A delivery row ({@code platform.delivery.due}). */
    DELIVERY;

    /** The kind of a record type; the parser only lets the two ADR-010 work types through. */
    public static WorkKind of(String type) {
        return switch (type) {
            case "message.accepted" -> MESSAGE;
            case "delivery.due" -> DELIVERY;
            default -> throw new IllegalArgumentException("not a work type: " + type);
        };
    }
}