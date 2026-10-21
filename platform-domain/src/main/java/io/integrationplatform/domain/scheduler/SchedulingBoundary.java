package io.integrationplatform.domain.scheduler;

/**
 * E14 scaffold (low-conflict, no final semantics): the boundary between scheduling internals (db-scheduler,
 * job rows, run rows) and callers such as the agent-facing read surface. Code outside the scheduler crosses this
 * boundary only through the types it exposes; scheduling decisions remain inside the implementation.
 *
 * <p>Marking and method sets here are intentionally minimal and carry no behaviour yet - later tasks replace the
 * internals without changing this fence.
 */
public interface SchedulingBoundary {

    /** Whether scheduling is currently enabled for the polled job. Default: enabled. */
    default boolean schedulingEnabled() {
        return true;
    }

    /** Hand a polling surface to the boundary without exposing the scheduler; default no-op. */
    default void attachPollingSurface(PollingJobSurface surface) {
        // no-op scaffold: final wiring lands with the scheduler runtime task
    }
}
