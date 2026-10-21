package io.integrationplatform.audit.api;

import io.integrationplatform.domain.audit.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * E12 seam (arjun-fullstack): one activity event as it is WRITTEN - the tenant-less counterpart of
 * {@link ActivityEvent} (which is the read projection: it adds the ledger id and carries the time as text).
 * {@code traceId} is the trace / correlation id of the causing request, or {@code null}.
 *
 * <p>Why not {@link AuditEvent}: that record is the domain's publish-side seam and names a {@code tenantId}, which a
 * writer must never trust (R08); an entry has none, so the only tenant is the bound one. {@link #from} is the mapping
 * between the two, so a config edit's {@code AuditEvent} can be recorded as activity without a second vocabulary.
 */
public record ActivityEntry(String actor, String action, List<String> targetIds, String before, String after,
        Instant at, String traceId) {

    public ActivityEntry {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        Objects.requireNonNull(at, "at");
        targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
    }

    /** The entry for a domain audit event: who becomes the actor, the tenant is dropped (the bound one applies). */
    public static ActivityEntry from(AuditEvent event, String traceId) {
        Objects.requireNonNull(event, "event");
        return new ActivityEntry(event.who(), event.action(), event.targetIds(), event.before(), event.after(),
                event.at(), traceId);
    }
}
