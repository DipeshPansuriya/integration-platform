package io.integrationplatform.domain.scheduler;

import java.time.Instant;
import java.util.List;

/**
 * E14 scaffold (low-conflict, no final semantics): the read-only surface an AI agent polls for jobs and their
 * scheduling state. The surface is intentionally data-only: job identity, state and scheduling metadata only -
 * never a payload, never a tenant id (ADR-012 sections 4 and 5). Implementations fill poll() later; this interface
 * fixes the shape so agent-side code and the scheduler interior agree on a stable boundary without binding either.
 *
 * <p>No scheduling behaviour is implemented here. This is a seam, not a scheduler.
 */
public interface PollingJobSurface {

    /** One polled job summary - identity and scheduling state only, no payload and no tenant data. */
    record JobSummary(String jobId, String jobType, String state, Instant nextRunAt, Instant lastRunAt) {
    }

    /** Poll the current job state. Implementations may return an empty list until wired; never null. */
    List<JobSummary> poll();

    /** Poll job state observed since {@code since}; defaults to a full {@link #poll()}. */
    default List<JobSummary> pollSince(Instant since) {
        return poll();
    }
}
