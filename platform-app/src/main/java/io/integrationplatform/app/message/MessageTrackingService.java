package io.integrationplatform.app.message;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.swagger.v3.oas.annotations.media.Schema;
import io.integrationplatform.config.flow.api.MaskedFields;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

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
 * <p><b>Failing stage</b> (T112 round 2, row 328 M1) - server-side state only, never a client hint: a message
 * {@code ROUTE_FAILED} failed at {@code ROUTE}; one with a {@code DEAD_LETTERED} delivery failed at {@code DELIVER};
 * otherwise null (in flight, delivered, accepted).
 *
 * <p><b>Deliveries</b> (row 329 M3): one entry per delivery of the message with the target's response time - the LAST
 * attempt's {@code finished_at - started_at} (sent to answered, Stage2AttemptWriter) and that attempt's outcome class;
 * both null when the delivery has no attempt yet. Not the Deliver stage, which includes our retry waits.
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
            Long totalMs, String failingStage, List<DeliveryTime> deliveries) {
    }

    /**
     * One delivery of a message: its target and the last attempt's sent-to-answered time and outcome (null: none).
     * T165 M1 (ADR-010 section 6): {@code state} is the delivery's own state, never null - the operator actions key on
     * it, {@code outcome} is display only. Still a subset of the row 329 contract (no attemptCount, lastAttempt{..}).
     */
    public record DeliveryTime(String deliveryId, String targetId, Long durationMs, String outcome,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryState state) {
    }

    /** S8 search hit: a bound-tenant message within the requested day; no payload. */
    public record SearchResult(String trackingId, String flowId, String state, String acceptedAt) {
    }

    /** One failed or dead-lettered message: where it failed, the error class and the last error text. */
    public record Failure(String trackingId, String deliveryId, String flowId, String targetId, String failingStage,
            String errorClass, String lastError, String origin, String failedAt) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** T156: read-only users see lastError; enough for a status line plus a diagnostic, not a whole target body. */
    static final int LAST_ERROR_MAX = 512;

    /**
     * T156: personal data and secrets the per-message masking cannot know (a target echoing an email or a token).
     * shortcut: emails, bearer/basic tokens and key=value secrets only; extend if a target leaks another shape.
     */
    private static final Pattern FREE_TEXT_SECRETS = Pattern.compile(
            "[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"
                    + "|(?i)(?<=\\b(?:bearer|basic)\\s)\\S+"
                    + "|(?i)(?<=\\b(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)\\s?[=:]\\s?)[^\\s,;&\"']+");

    private final JdbcMessageTrackingStore store;

    public MessageTrackingService(JdbcMessageTrackingStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The bound tenant's message by tracking id with its stages; empty when the tenant cannot see it. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<Timeline> timeline(UUID trackingId) {
        return store.message(trackingId).map(message -> {
            List<JdbcMessageTrackingStore.StageRow> rows = store.stages(trackingId).stream()
                    .sorted(Comparator.comparing(JdbcMessageTrackingStore.StageRow::startedAt,
                                    Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                            .thenComparing(row -> pipelineIndex(row.stage())))
                    .toList();
            List<StageTime> stages = new ArrayList<>();
            Instant previousEnd = null;
            Instant latest = null;
            for (JdbcMessageTrackingStore.StageRow row : rows) {
                Instant end = row.finishedAt() != null ? row.finishedAt()
                        : row.startedAt() != null ? row.startedAt() : null;
                stages.add(new StageTime(row.stage(),
                        row.startedAt() == null ? null : row.startedAt().toString(), text(row.finishedAt()),
                        row.startedAt() == null || row.finishedAt() == null ? null
                                : millis(row.startedAt(), row.finishedAt()),
                        previousEnd == null || row.startedAt() == null ? null
                                : millis(previousEnd, row.startedAt())));
                if (end != null) {
                    previousEnd = end;
                    latest = latest == null || end.isAfter(latest) ? end : latest;
                }
            }
            Instant firstStart = rows.isEmpty() ? null : rows.get(0).startedAt();
            Long total = firstStart == null || latest == null ? null : millis(firstStart, latest);
            List<JdbcMessageTrackingStore.DeliveryAttemptRow> deliveryRows = store.deliveries(trackingId);
            return new Timeline(message.id(), message.flowId(), message.state(), text(message.acceptedAt()), stages,
                    total, failingStage(message.state(), deliveryRows), deliveryTimes(deliveryRows));
        });
    }

    /** Bounded business-key (or recent) search in a single UTC day for the bound tenant; never payload. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true, timeout = 1)
    public List<SearchResult> search(String businessKey, UUID flowId, LocalDate day) {
        LocalDate d = day == null ? LocalDate.now(java.time.ZoneOffset.UTC) : day;
        java.time.Instant dayStart = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.time.Instant dayEnd = d.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        UUID flowUuid = flowId;
        List<JdbcMessageTrackingStore.MessageRow> rows = store.search(
                (businessKey == null || businessKey.isBlank()) ? null : businessKey,
                flowUuid,
                dayStart,
                dayEnd);
        List<SearchResult> result = new ArrayList<>();
        for (JdbcMessageTrackingStore.MessageRow row : rows) {
            try {
                result.add(new SearchResult(row.id(), row.flowId(), row.state(), text(row.acceptedAt())));
            } catch (IllegalArgumentException ignored) {
                // ignore malformed ids
            }
        }
        return result;
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
        List<JdbcMessageTrackingStore.DeadLetterRow> deadLetters = store.deadLetters(flowId, dayStart, dayEnd);
        // row 278 M6: one masked-source read for the whole page, not one per dead letter
        Map<String, JdbcMessageTrackingStore.MaskedSource> sources = store.maskedSources(deadLetters.stream()
                .map(JdbcMessageTrackingStore.DeadLetterRow::messageId).filter(Objects::nonNull).distinct().toList());
        for (JdbcMessageTrackingStore.DeadLetterRow row : deadLetters) {
            boolean namedReason = row.deadLetterDetail() != null
                    && !row.deadLetterDetail().equals(row.deadLetterClass());
            boolean targetAnswer = !namedReason && row.attemptDetail() != null;
            // a null id must not reach Map.of().get(null), which throws
            JdbcMessageTrackingStore.MaskedSource source = row.messageId() == null ? null : sources.get(row.messageId());
            String lastError = masked(source, targetAnswer ? row.attemptDetail() : row.deadLetterDetail());
            failures.add(Map.entry(row.failedAt(), new Failure(row.messageId(), row.id(), row.flowId(),
                    row.targetId(), "DELIVER", row.deadLetterClass(), lastError, targetAnswer ? TARGET : PLATFORM,
                    text(row.failedAt()))));
        }
        return failures.stream()
                .sorted(Map.Entry.<Instant, Failure>comparingByKey().reversed())
                .map(Map.Entry::getValue)
                .toList();
    }

    /** ROUTE_FAILED is ROUTE; any DEAD_LETTERED delivery is DELIVER; otherwise nothing failed. */
    private static String failingStage(String state, List<JdbcMessageTrackingStore.DeliveryAttemptRow> deliveries) {
        if ("ROUTE_FAILED".equals(state)) {
            return "ROUTE";
        }
        return deliveries.stream().anyMatch(row -> "DEAD_LETTERED".equals(row.state())) ? "DELIVER" : null;
    }

    /** One entry per delivery, ordered by target then delivery id; the store already picked each last attempt. */
    private static List<DeliveryTime> deliveryTimes(List<JdbcMessageTrackingStore.DeliveryAttemptRow> rows) {
        Map<String, JdbcMessageTrackingStore.DeliveryAttemptRow> last = new LinkedHashMap<>();
        for (JdbcMessageTrackingStore.DeliveryAttemptRow row : rows) {
            last.merge(row.deliveryId(), row, MessageTrackingService::later);
        }
        return last.values().stream()
                .sorted(Comparator.comparing(JdbcMessageTrackingStore.DeliveryAttemptRow::targetId,
                                Comparator.nullsLast(Comparator.<String>naturalOrder()))
                        .thenComparing(JdbcMessageTrackingStore.DeliveryAttemptRow::deliveryId))
                .map(row -> new DeliveryTime(row.deliveryId(), row.targetId(),
                        row.attemptId() == null || row.startedAt() == null || row.finishedAt() == null ? null
                                : millis(row.startedAt(), row.finishedAt()),
                        row.attemptId() == null ? null : row.outcomeClass(),
                        DeliveryState.valueOf(row.state())))
                .toList();
    }

    private static JdbcMessageTrackingStore.DeliveryAttemptRow later(JdbcMessageTrackingStore.DeliveryAttemptRow a,
            JdbcMessageTrackingStore.DeliveryAttemptRow b) {
        if (a.attemptId() == null) {
            return b;
        }
        if (b.attemptId() == null) {
            return a;
        }
        int byFinish = Comparator.<Instant>nullsFirst(Comparator.naturalOrder()).compare(a.finishedAt(),
                b.finishedAt());
        if (byFinish != 0) {
            return byFinish > 0 ? a : b;
        }
        return a.attemptId() >= b.attemptId() ? a : b;
    }

    /** T-066 (T108): an error text with the message's own masked values (and any registered one) replaced. */
    private static String masked(JdbcMessageTrackingStore.MaskedSource source, String text) {
        if (text == null) {
            return null;
        }
        String redacted = Optional.ofNullable(source)
                .filter(s -> s.bodyJson() != null && s.maskedFieldsJson() != null)
                .map(s -> MaskedFields.redact(text, MaskedFields.valuesOf(JSON.readTree(s.bodyJson()),
                        MaskedFields.fromJson(s.maskedFieldsJson()))))
                .orElse(text);
        String scrubbed = FREE_TEXT_SECRETS.matcher(MaskedFields.scrub(redacted)).replaceAll(MaskedFields.MARKER);
        // cap after redaction so a cut can never leave half a secret unmatched
        return scrubbed.length() <= LAST_ERROR_MAX ? scrubbed : scrubbed.substring(0, LAST_ERROR_MAX - 3) + "...";
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
