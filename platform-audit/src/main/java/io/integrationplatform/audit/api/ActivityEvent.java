package io.integrationplatform.audit.api;

import java.util.List;

/**
 * E12 seam (arjun-fullstack): one audit event as the tenant activity view shows it. Java 25 record; plain data, no Spring.
 * {@code at} is the event time as a UTC instant string. Cross-tenant visibility is enforced by the reader.
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