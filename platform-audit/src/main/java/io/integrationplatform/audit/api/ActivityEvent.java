package io.integrationplatform.audit.api;

import java.util.List;

/**
 * E12 seam (arjun-fullstack): one audit event as the tenant activity view shows it. Java 25 record; plain data, no Spring.
 * {@code at} is the event time as a UTC instant string. Cross-tenant visibility is enforced by the reader.
 *
 * <p>This is the READ projection. It is not {@code io.integrationplatform.domain.audit.AuditEvent} (the domain's
 * publish-side record, which names a tenant): an activity event never carries a tenant, and the write side takes
 * an {@link ActivityEntry} that maps from an {@code AuditEvent}.
 * {@code traceId} is the trace / correlation id the writer recorded, or {@code null}; written via {@link ActivityWriteApi}.
 */
public record ActivityEvent(long id, String actor, String action, List<String> targetIds, String before,
        String after, String at, String traceId) {

    /** An event with no trace id (the read contract before the write side existed). */
    public ActivityEvent(long id, String actor, String action, List<String> targetIds, String before, String after,
            String at) {
        this(id, actor, action, targetIds, before, after, at, null);
    }
}
