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

    /**
     * One polled job summary - identity and scheduling state only, no payload and no tenant data. {@code pausedAt}
     * is when the job was paused (null while it is not paused); it is the only state-change timestamp the register
     * keeps.
     */
    record JobSummary(String jobId, String jobType, String state, Instant nextRunAt, Instant lastRunAt,
            Instant pausedAt) {

        /** A summary without a pause timestamp (additive: the pre-pausedAt shape stays constructible). */
        public JobSummary(String jobId, String jobType, String state, Instant nextRunAt, Instant lastRunAt) {
            this(jobId, jobType, state, nextRunAt, lastRunAt, null);
        }
    }

    /** Poll the current job state. Implementations may return an empty list until wired; never null. */
    List<JobSummary> poll();

    /**
     * Poll the jobs that changed since {@code since}. "Changed" means the job <em>ran</em> since the cutoff (its
     * {@code lastRunAt} is at or after {@code since}) or was <em>paused</em> since it (its {@code pausedAt} is).
     * {@code nextRunAt} is deliberately not a change - every scheduled job has a future one, so counting it would
     * return the whole register on every call. State alone does not count: a job that is merely DISABLED has no
     * timestamp for when it was disabled, so it appears only when it ran. A {@code null} {@code since} degrades to
     * the full {@link #poll()}.
     */
    default List<JobSummary> pollSince(Instant since) {
        if (since == null) {
            return poll();
        }
        return poll().stream().filter(summary -> changedSince(summary, since)).toList();
    }

    /** True when the job ran, or was paused, at or after {@code since}. */
    static boolean changedSince(JobSummary summary, Instant since) {
        return ranSince(summary, since) || pausedSince(summary, since);
    }

    /** True when the job was paused at or after {@code since}. */
    static boolean pausedSince(JobSummary summary, Instant since) {
        return summary.pausedAt() != null && !summary.pausedAt().isBefore(since);
    }

    /** True when the job's last run is at or after {@code since}. */
    static boolean ranSince(JobSummary summary, Instant since) {
        return summary.lastRunAt() != null && !summary.lastRunAt().isBefore(since);
    }
}
