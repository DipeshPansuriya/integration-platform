package io.integrationplatform.domain.audit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * No human actor - an in-process caller (the nil uuid). The one-argument flow publish audits under it, so no
     * publish path skips the audit.
     */
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    public static final String FLOW_CREATED = "FLOW_CREATED";
    public static final String FLOW_DRAFT_SAVED = "FLOW_DRAFT_SAVED";
    public static final String FLOW_SUBMITTED = "FLOW_SUBMITTED";
    public static final String FLOW_APPROVED = "FLOW_APPROVED";
    public static final String FLOW_PUBLISHED = "FLOW_PUBLISHED";
    public static final String MAPPING_SAVED = "MAPPING_SAVED";
    public static final String RULE_SET_CREATED = "RULE_SET_CREATED";
    public static final String RULE_SET_UPDATED = "RULE_SET_UPDATED";
    public static final String CONTRACT_DRAFT_SAVED = "CONTRACT_DRAFT_SAVED";
    public static final String CONTRACT_SUBMITTED = "CONTRACT_SUBMITTED";
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
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorId, "actorId");
        return new AuditEvent(tenantId, actorId.toString(), action, List.copyOf(targetIds), before, after,
                Instant.now());
    }

    /** A target reference {@code kind:id}. */
    public static String target(String kind, Object id) {
        return kind + ":" + Objects.requireNonNull(id, kind);
    }

    /**
     * A flat JSON object of state facts for before/after, from name/value pairs. A value is a string, a number, a
     * boolean, a list of strings or {@code null}; strings are JSON-escaped.
     */
    public static String state(Object... namesAndValues) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            json.append(i == 0 ? "" : ",").append(quoted(String.valueOf(namesAndValues[i]))).append(':')
                    .append(value(namesAndValues[i + 1]));
        }
        return json.append('}').toString();
    }

    private static String value(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(item -> quoted(String.valueOf(item))).collect(Collectors.joining(",", "[", "]"));
        }
        return quoted(value.toString());
    }

    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }
}
