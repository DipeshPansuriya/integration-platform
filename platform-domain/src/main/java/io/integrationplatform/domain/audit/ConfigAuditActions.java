package io.integrationplatform.domain.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * E12-F2-S1 (T-068 + T-134, row 327 T117): the audit actions a completed configuration change, contract approval
 * or credential action publishes, and the one shape their {@link AuditEvent} takes - who (the acting user id),
 * what (the action), when, tenant, target references ({@code kind:id}, the {@link AuditActions} convention) and
 * before/after state where relevant. Before/after carry ids, statuses, versions and names only - never a secret,
 * a credential value or a payload value (R14). A plain class: platform-domain depends on no Spring (ADR-001); the
 * caller publishes with {@code ApplicationEventPublisher} inside its write transaction and the append-only writer
 * persists it after commit.
 */
public final class ConfigAuditActions {

    public static final String FLOW_CREATED = "FLOW_CREATED";
    public static final String FLOW_DRAFT_SAVED = "FLOW_DRAFT_SAVED";
    public static final String FLOW_SUBMITTED = "FLOW_SUBMITTED";
    public static final String FLOW_APPROVED = "FLOW_APPROVED";
    public static final String FLOW_PUBLISHED = "FLOW_PUBLISHED";
    public static final String MAPPING_SAVED = "MAPPING_SAVED";
    public static final String RULE_SET_CREATED = "RULE_SET_CREATED";
    public static final String RULE_SET_UPDATED = "RULE_SET_UPDATED";
    public static final String CONTRACT_VERSION_IMPORTED = "CONTRACT_VERSION_IMPORTED";
    public static final String CONTRACT_VERSION_APPROVED = "CONTRACT_VERSION_APPROVED";
    public static final String CREDENTIAL_SOURCE_ISSUED = "CREDENTIAL_SOURCE_ISSUED";
    public static final String CREDENTIAL_SOURCE_ROTATED = "CREDENTIAL_SOURCE_ROTATED";
    public static final String CREDENTIAL_SOURCE_REVOKED = "CREDENTIAL_SOURCE_REVOKED";
    public static final String CREDENTIAL_TARGET_SAVED = "CREDENTIAL_TARGET_SAVED";

    private ConfigAuditActions() {
    }

    /** One event, stamped now: tenant, actor, action and every target reference are required. */
    public static AuditEvent event(String action, UUID tenantId, UUID actorId, List<String> targetIds,
            String before, String after) {
        throw new UnsupportedOperationException("RED");
    }

    /** A target reference {@code kind:id}. */
    public static String target(String kind, Object id) {
        throw new UnsupportedOperationException("RED");
    }

    /**
     * A flat JSON object of state facts for before/after, from name/value pairs. A value is a string, a number, a
     * boolean, a list of strings or {@code null}; strings are JSON-escaped.
     */
    public static String state(Object... namesAndValues) {
        throw new UnsupportedOperationException("RED");
    }
}
