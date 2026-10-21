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
     * is when the job was paused (null while it is not paused). {@code stateChangedAt} is when its enabled / paused
     * state last changed (a disable, an enable, a pause or a resume; null when the source does not know) - unlike
     * {@code pausedAt} it survives a resume and sees a job that was only disabled.
     */
    record JobSummary(String jobId, String jobType, String state, Instant nextRunAt, Instant lastRunAt,
            Instant pausedAt, Instant stateChangedAt) {

        /** A summary without a pause timestamp (additive: the pre-pausedAt shape stays constructible). */
        public JobSummary(String jobId, String jobType, String state, Instant nextRunAt, Instant lastRunAt) {
            this(jobId, jobType, state, nextRunAt, lastRunAt, null, null);
        }

        /** A summary without a state-change stamp (additive: the pre-stateChangedAt shape stays constructible). */
        public JobSummary(String jobId, String jobType, String state, Instant nextRunAt, Instant lastRunAt,
                Instant pausedAt) {
            this(jobId, jobType, state, nextRunAt, lastRunAt, pausedAt, null);
        }
    }

    /** Poll the current job state. Implementations may return an empty list until wired; never null. */
    List<JobSummary> poll();

    /**
     * Poll the jobs that changed since {@code since}. "Changed" means the job <em>ran</em> since the cutoff (its
     * {@code lastRunAt} is at or after {@code since}), was <em>paused</em> since it (its {@code pausedAt} is), or had
     * its enabled / paused <em>state changed</em> since it (its {@code stateChangedAt} is) - the last one also sees a
     * job that was only disabled or only resumed. {@code nextRunAt} is deliberately not a change - every scheduled
     * job has one, so counting it would return the whole register on every call. State alone does not count: a
     * summary with no timestamps (an older source) appears only when it ran. A {@code null} {@code since} degrades
     * to the full {@link #poll()}.
     */
    default List<JobSummary> pollSince(Instant since) {
        if (since == null) {
            return poll();
        }
        return poll().stream().filter(summary -> changedSince(summary, since)).toList();
    }

    /** True when the job ran, was paused, or had its state changed, at or after {@code since}. */
    static boolean changedSince(JobSummary summary, Instant since) {
        return ranSince(summary, since) || stateChangedSince(summary, since) || pausedSince(summary, since);
    }

    /** True when the job's enabled / paused state changed at or after {@code since}. */
    static boolean stateChangedSince(JobSummary summary, Instant since) {
        return summary.stateChangedAt() != null && !summary.stateChangedAt().isBefore(since);
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
