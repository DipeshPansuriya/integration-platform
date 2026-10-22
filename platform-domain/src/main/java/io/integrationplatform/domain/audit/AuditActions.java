package io.integrationplatform.domain.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * E12 N3 (T-067 narrow, row278 wave): the audit actions the operator delivery actions publish, with the one shape
 * their events take. {@code OPERATOR_RETRY} re-drives a delivery that waited for retry; {@code OPERATOR_REPLAY}
 * moves a dead-lettered delivery back to {@code DUE} with the versions it arrived with (T-166, E13 N2, publishes
 * through these factories). The pinned versions are carried in {@code target_ids} - the delivery id and the mapping
 * and contract version ids the action used - because the {@link AuditEvent} shape is architecture's and is not
 * widened here (the record's javadoc). A plain class: platform-domain depends on no Spring (ADR-001); the caller
 * publishes with {@code ApplicationEventPublisher} and the append-only writer (E12 N3) persists it.
 */
public final class AuditActions {

    /** An operator retried a waiting delivery through the retry action (E13 N2 endpoint). */
    public static final String OPERATOR_RETRY = "OPERATOR_RETRY";

    /** An operator replayed a dead-lettered delivery with its pinned versions (T-166). */
    public static final String OPERATOR_REPLAY = "OPERATOR_REPLAY";

    private AuditActions() {
    }

    /** One retry event: actor, tenant, the delivery and the pinned mapping and contract versions re-driven. */
    public static AuditEvent operatorRetry(String actor, UUID tenantId, UUID deliveryId,
            UUID mappingVersionId, UUID contractVersionId, Instant at) {
        return deliveryAction(OPERATOR_RETRY, actor, tenantId, deliveryId, mappingVersionId, contractVersionId, at);
    }

    /** One replay event: actor, tenant, the delivery and the pinned mapping and contract versions replayed. */
    public static AuditEvent operatorReplay(String actor, UUID tenantId, UUID deliveryId,
            UUID mappingVersionId, UUID contractVersionId, Instant at) {
        return deliveryAction(OPERATOR_REPLAY, actor, tenantId, deliveryId, mappingVersionId, contractVersionId, at);
    }

    private static AuditEvent deliveryAction(String action, String actor, UUID tenantId, UUID deliveryId,
            UUID mappingVersionId, UUID contractVersionId, Instant at) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(at, "at");
        // a route without a mapping (T172) has no mapping version to name; the contract pin is always there
        List<String> targets = mappingVersionId == null
                ? List.of("delivery:" + deliveryId, "contract_version:" + contractVersionId)
                : List.of("delivery:" + deliveryId, "mapping_version:" + mappingVersionId,
                        "contract_version:" + contractVersionId);
        return new AuditEvent(tenantId, actor, action, targets, null, null, at);
    }
}
