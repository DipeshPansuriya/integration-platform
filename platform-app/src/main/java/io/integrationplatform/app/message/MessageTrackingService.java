package io.integrationplatform.app.message;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 N1 (T110; T-137 API narrow + the E12 failure-diagnosis read side): one message's journey by tracking id, and
 * the failed work per flow and UTC day - both read LIVE from {@link JdbcMessageTrackingStore} inside the caller's
 * tenant-stamped read transaction, so a tenant only ever sees its own rows (RLS + the base's own predicate).
 *
 * <p><b>Timeline</b>: every recorded stage (V2_1_1110's {@code message_stage_timing}), ordered by start and then by
 * pipeline order, with its own duration ({@code finished - started}, null while running) and the gap since the
 * previous stage ended ({@code started - previous finished}, or the previous start while that stage still runs);
 * {@code totalMs} runs from the first start to the latest instant recorded.
 *
 * <p><b>Failures</b>, newest first:
 * <ul>
 *   <li>a message {@code ROUTE_FAILED} - stage {@code ROUTE}, class {@code ROUTE_FAILED}, origin {@code PLATFORM}
 *       (no error text is stored on the message today; the routing writer is a later lane's);</li>
 *   <li>a delivery {@code DEAD_LETTERED} - stage {@code DELIVER}, its {@code dead_letter_class}; the last error text
 *       is the platform's own named reason when the DEAD_LETTER event carries one (origin {@code PLATFORM}, e.g. an
 *       unreadable target credential - our-side errors are in the list), otherwise the target's last non-success
 *       answer (origin {@code TARGET}).</li>
 * </ul>
 */
public class MessageTrackingService {

    /** The pipeline's closed stage set in order (V2_1_1110) - the tie-break when two stages start together. */
    private static final List<String> PIPELINE = List.of("INTAKE", "ROUTE", "TRANSFORM", "DELIVER");

    static final String PLATFORM = "PLATFORM";
    static final String TARGET = "TARGET";

    /** One recorded stage of a message: its timestamps, its own duration and the gap since the previous stage. */
    public record StageTime(String stage, String startedAt, String finishedAt, Long durationMs, Long sincePreviousMs) {
    }

    /** One message's journey: every recorded stage in pipeline order, and the total from first start to last end. */
    public record Timeline(String trackingId, String flowId, String state, String acceptedAt, List<StageTime> stages,
            Long totalMs) {
    }

    /** One failed or dead-lettered message: where it failed, the error class and the last error text. */
    public record Failure(String trackingId, String deliveryId, String flowId, String targetId, String failingStage,
            String errorClass, String lastError, String origin, String failedAt) {
    }

    private final JdbcMessageTrackingStore store;

    public MessageTrackingService(JdbcMessageTrackingStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The bound tenant's message by tracking id with its stages; empty when the tenant cannot see it. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<Timeline> timeline(UUID trackingId) {
        return store.message(trackingId).map(message -> {
            List<JdbcMessageTrackingStore.StageRow> rows = store.stages(trackingId).stream()
                    .sorted(Comparator.comparing(JdbcMessageTrackingStore.StageRow::startedAt)
                            .thenComparing(row -> pipelineIndex(row.stage())))
                    .toList();
            List<StageTime> stages = new ArrayList<>();
            Instant previousEnd = null;
            Instant latest = null;
            for (JdbcMessageTrackingStore.StageRow row : rows) {
                Instant end = row.finishedAt() != null ? row.finishedAt() : row.startedAt();
                stages.add(new StageTime(row.stage(), row.startedAt().toString(), text(row.finishedAt()),
                        row.finishedAt() == null ? null : millis(row.startedAt(), row.finishedAt()),
                        previousEnd == null ? null : millis(previousEnd, row.startedAt())));
                previousEnd = end;
                latest = latest == null || end.isAfter(latest) ? end : latest;
            }
            Long total = rows.isEmpty() ? null : millis(rows.get(0).startedAt(), latest);
            return new Timeline(message.id(), message.flowId(), message.state(), text(message.acceptedAt()), stages,
                    total);
        });
    }

    /** The bound tenant's failed work, optionally narrowed to one flow and one UTC day, newest first. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Failure> failures(UUID flowId, LocalDate day) {
        Instant dayStart = day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = day == null ? null : day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Map.Entry<Instant, Failure>> failures = new ArrayList<>();
        for (JdbcMessageTrackingStore.RouteFailureRow row : store.routeFailures(flowId, dayStart, dayEnd)) {
            failures.add(Map.entry(row.failedAt(), new Failure(row.id(), null, row.flowId(), null, "ROUTE",
                    "ROUTE_FAILED", null, PLATFORM, text(row.failedAt()))));
        }
        for (JdbcMessageTrackingStore.DeadLetterRow row : store.deadLetters(flowId, dayStart, dayEnd)) {
            boolean namedReason = row.deadLetterDetail() != null
                    && !row.deadLetterDetail().equals(row.deadLetterClass());
            boolean targetAnswer = !namedReason && row.attemptDetail() != null;
            String lastError = targetAnswer ? row.attemptDetail() : row.deadLetterDetail();
            failures.add(Map.entry(row.failedAt(), new Failure(row.messageId(), row.id(), row.flowId(),
                    row.targetId(), "DELIVER", row.deadLetterClass(), lastError, targetAnswer ? TARGET : PLATFORM,
                    text(row.failedAt()))));
        }
        return failures.stream()
                .sorted(Map.Entry.<Instant, Failure>comparingByKey().reversed())
                .map(Map.Entry::getValue)
                .toList();
    }

    private static int pipelineIndex(String stage) {
        int index = PIPELINE.indexOf(stage);
        return index < 0 ? PIPELINE.size() : index;
    }

    private static long millis(Instant from, Instant to) {
        return Duration.between(from, to).toMillis();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
