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

    /**
     * Poll job state observed since {@code since}. A real incremental read: only summaries carrying an observed
     * scheduling time ({@code lastRunAt} or {@code nextRunAt}) at or after {@code since} are returned, so a caller
     * that already saw an earlier snapshot receives just the changed tail instead of the full register. A {@code null}
     * {@code since} and an implementation without such timestamps both degrade to the full {@link #poll()}.
     */
    default List<JobSummary> pollSince(Instant since) {
        if (since == null) {
            return poll();
        }
        return poll().stream()
                .filter(summary -> (summary.lastRunAt() != null && !summary.lastRunAt().isBefore(since))
                        || (summary.nextRunAt() != null && !summary.nextRunAt().isBefore(since)))
                .toList();
    }
}
