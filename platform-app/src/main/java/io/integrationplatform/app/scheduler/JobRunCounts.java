package io.integrationplatform.app.scheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * E14 T-059 / T-142 (ADR-012 section 4): the aggregate counts a job body reports for its {@code scheduler.job_run}
 * row. A body {@link #put}s numbers while it runs; the recording listener {@link #take}s them when the run completes -
 * db-scheduler calls both on the executing thread, so the slate is thread-bound and two jobs never mix. The run
 * wrapper {@link #clear}s it before each body so a pooled thread never carries an earlier run's numbers.
 *
 * <p>Counts are plain names to long values: no tenant id, no payload (the table's own rule). The names are fixed
 * words chosen by the body, never data.
 */
@Component
public class JobRunCounts {

    private final ThreadLocal<Map<String, Long>> slate = new ThreadLocal<>();

    /** Records one named count for the run executing on this thread. */
    public void put(String name, long value) {
        Map<String, Long> counts = slate.get();
        if (counts == null) {
            counts = new LinkedHashMap<>();
            slate.set(counts);
        }
        counts.put(name, value);
    }

    /** Forgets whatever this thread recorded. */
    public void clear() {
        slate.remove();
    }

    /** The recorded counts as a JSON object text, or null when none were recorded; the slate is emptied. */
    public String take() {
        Map<String, Long> counts = slate.get();
        slate.remove();
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        counts.forEach((name, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(name).append("\":").append(value);
        });
        return json.append('}').toString();
    }
}
