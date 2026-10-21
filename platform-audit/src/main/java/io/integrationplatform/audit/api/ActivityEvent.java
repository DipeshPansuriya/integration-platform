package io.integrationplatform.audit.api;

import java.util.List;

/**
 * E12 seam (arjun-fullstack): one audit event as the tenant activity view shows it. Java 25 record; plain data, no Spring.
 * {@code at} is the event time as a UTC instant string. Cross-tenant visibility is enforced by the reader.
 */
public record ActivityEvent(long id, String actor, String action, List<String> targetIds, String before,
        String after, String at) {
}