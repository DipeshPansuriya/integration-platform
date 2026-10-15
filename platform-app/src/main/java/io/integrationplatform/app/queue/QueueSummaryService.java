package io.integrationplatform.app.queue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F10-narrow (row278 release-base plan step 8): assembles the queue dashboard's four figures per flow + day,
 * derived LIVE from {@link JdbcQueueSummaryStore}'s reads of the message/delivery state registers - no
 * hand-maintained counters anywhere (the narrow scope's own ruling; aggregate maintenance is a later lane's).
 *
 * <p>The closed derivation (the state words are the tables' CHECK registers, R10):
 * <ul>
 *   <li><b>queued</b> = a message still {@code ACCEPTED} (not yet through routing) + a delivery in a non-final
 *       state ({@code PENDING}, {@code HELD}, {@code SENDING}, {@code RETRY_WAIT}, {@code DUE}) - the work the
 *       platform is holding right now;</li>
 *   <li><b>processed</b> = a message {@code ROUTED} (the routing plane fanned it out);</li>
 *   <li><b>converted</b> = a delivery {@code DELIVERED} (its contract-translated output reached the target);</li>
 *   <li><b>failed</b> = a message {@code ROUTE_FAILED} + a delivery {@code DEAD_LETTERED}.</li>
 * </ul>
 *
 * <p>Reads run in the caller's tenant-stamped transaction ({@code readOnly}); the dashboard only ever sees the
 * bound tenant's rows (RLS + the base's own predicate).
 */
public class QueueSummaryService {

    /** One dashboard row: a flow + day with the four live counts. */
    public record Row(String flowId, String flowCode, String flowName, String day,
            long queued, long processed, long converted, long failed) {
    }

    private final JdbcQueueSummaryStore store;

    public QueueSummaryService(JdbcQueueSummaryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The (flow, day) work groups of the bound tenant, optionally narrowed to one flow and one UTC day. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Row> summary(UUID flowId, LocalDate day) {
        Map<String, long[]> counts = new LinkedHashMap<>(); // "flowId|day" -> {queued, processed, converted, failed}
        for (JdbcQueueSummaryStore.StageRow row : store.messageRows(flowId, day)) {
            if (row.flowId() == null) {
                continue; // the dashboard counts work per flow (named limit: ownerless rows stay out)
            }
            long[] c = bucket(counts, row.flowId(), row.day());
            switch (row.state()) {
                case "ACCEPTED" -> c[0]++;
                case "ROUTED" -> c[1]++;
                case "ROUTE_FAILED" -> c[3]++;
                default -> throw new IllegalStateException("unknown message register " + row.state());
            }
        }
        for (JdbcQueueSummaryStore.StageRow row : store.deliveryRows(flowId, day)) {
            if (row.flowId() == null) {
                continue; // the dashboard counts work per flow (named limit: ownerless rows stay out)
            }
            long[] c = bucket(counts, row.flowId(), row.day());
            switch (row.state()) {
                case "PENDING", "HELD", "SENDING", "RETRY_WAIT", "DUE" -> c[0]++;
                case "DELIVERED" -> c[2]++;
                case "DEAD_LETTERED" -> c[3]++;
                default -> throw new IllegalStateException("unknown delivery register " + row.state());
            }
        }

        List<Row> rows = new ArrayList<>();
        for (JdbcQueueSummaryStore.FlowLabel flow : store.flowLabels()) {
            counts.keySet().stream()
                    .filter((key) -> key.startsWith(flow.id() + "|"))
                    .sorted()
                    .forEach((key) -> {
                        long[] c = counts.get(key);
                        rows.add(new Row(flow.id(), flow.code(), flow.name(), key.substring(flow.id().length() + 1),
                                c[0], c[1], c[2], c[3]));
                    });
        }
        return rows;
    }

    /** The four counters of one (flow, day) group, created 0-filled. */
    private static long[] bucket(Map<String, long[]> counts, String flowId, String day) {
        return counts.computeIfAbsent(flowId + "|" + day, (key) -> new long[4]);
    }
}