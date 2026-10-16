package io.integrationplatform.app.message;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E13 N1 (T110): READ-ONLY reads of one message's journey and of the failed work - the E8 message store
 * ({@code public.message}), its stage timings ({@code public.message_stage_timing}, V2_1_1110) and the E2 delivery
 * family ({@code public.delivery}, {@code delivery_attempt}, {@code delivery_event}). Nothing here writes; the
 * tables' owners (platform-routing / platform-ingest / platform-delivery) keep their writers.
 *
 * <p>Every statement runs through the tenant-scoped base (R08/R09/R11): the tenant predicate is the base's, the
 * tenant comes only from {@link TenantContext}, and every read sits inside the caller's tenant-stamped transaction,
 * so row-level security is the second gate - including inside the correlated sub-selects of the dead-letter read.
 *
 * <p>The day is the UTC day of {@code accepted_at} / {@code created_at} (delivery.created_at IS the message's
 * accepted_at per ADR-014), bound as a half-open range so the partitions prune.
 */
final class JdbcMessageTrackingStore extends TenantScopedRepository {

    /** The message row a timeline hangs on: the tracking id IS the message id (ADR-011 D7). */
    record MessageRow(String id, String flowId, String state, Instant acceptedAt) {
    }

    /** One stage timing row; {@code finishedAt} is null while the stage still runs. */
    record StageRow(String stage, Instant startedAt, Instant finishedAt) {
    }

    /** A message routing refused (our side: no delivery was ever created). */
    record RouteFailureRow(String id, String flowId, Instant failedAt) {
    }

    /**
     * A dead-lettered delivery with its two error texts: the latest non-success attempt's detail (what the target
     * answered) and the DEAD_LETTER event's detail (the class alone, or {@code CLASS: named reason} when the platform
     * itself refused the send).
     */
    record DeadLetterRow(String id, String messageId, String flowId, String targetId, String deadLetterClass,
            String attemptDetail, String deadLetterDetail, Instant failedAt) {
    }

    JdbcMessageTrackingStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    Optional<MessageRow> message(UUID id) {
        return query("select id::text as id, interface_id::text as interface_id, state, accepted_at"
                        + " from public.message",
                "id = :id::uuid", Map.of("id", id.toString()),
                (rs, n) -> new MessageRow(rs.getString("id"), rs.getString("interface_id"), rs.getString("state"),
                        instant(rs, "accepted_at")))
                .stream().findFirst();
    }

    List<StageRow> stages(UUID messageId) {
        return query("select stage, started_at, finished_at from public.message_stage_timing",
                "message_id = :message_id::uuid", Map.of("message_id", messageId.toString()),
                (rs, n) -> new StageRow(rs.getString("stage"), instant(rs, "started_at"),
                        instant(rs, "finished_at")));
    }

    List<RouteFailureRow> routeFailures(UUID flowId, Instant dayStart, Instant dayEnd) {
        Map<String, Object> params = new LinkedHashMap<>();
        return query("select id::text as id, interface_id::text as interface_id, updated_at from public.message",
                narrowed("state = 'ROUTE_FAILED'", "interface_id", "accepted_at", flowId, dayStart, dayEnd, params),
                params,
                (rs, n) -> new RouteFailureRow(rs.getString("id"), rs.getString("interface_id"),
                        instant(rs, "updated_at")));
    }

    List<DeadLetterRow> deadLetters(UUID flowId, Instant dayStart, Instant dayEnd) {
        Map<String, Object> params = new LinkedHashMap<>();
        return query("select d.id::text as id, d.message_id::text as message_id, d.interface_id::text as"
                        + " interface_id, d.target_id::text as target_id, d.dead_letter_class, d.updated_at,"
                        + " (select a.detail from public.delivery_attempt a where a.delivery_id = d.id"
                        + "   and a.outcome_class <> 'SUCCESS' and a.detail is not null"
                        + "   order by a.finished_at desc, a.id desc limit 1) as attempt_detail,"
                        + " (select e.detail from public.delivery_event e where e.delivery_id = d.id"
                        + "   and e.type = 'DEAD_LETTER' order by e.occurred_at desc, e.id desc limit 1)"
                        + "   as dead_letter_detail"
                        + " from public.delivery d",
                narrowed("d.state = 'DEAD_LETTERED'", "d.interface_id", "d.created_at", flowId, dayStart, dayEnd,
                        params),
                params,
                (rs, n) -> new DeadLetterRow(rs.getString("id"), rs.getString("message_id"),
                        rs.getString("interface_id"), rs.getString("target_id"), rs.getString("dead_letter_class"),
                        rs.getString("attempt_detail"), rs.getString("dead_letter_detail"),
                        instant(rs, "updated_at")));
    }

    /** The base condition, narrowed to one flow and one UTC day when given (constant fragments only). */
    private static String narrowed(String base, String flowColumn, String timeColumn, UUID flowId,
            Instant dayStart, Instant dayEnd, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>(List.of(base));
        if (flowId != null) {
            conditions.add(flowColumn + " = :flow_id::uuid");
            params.put("flow_id", flowId.toString());
        }
        if (dayStart != null) {
            conditions.add(timeColumn + " >= :day_start and " + timeColumn + " < :day_end");
            // the repo-wide binding convention: pgjdbc infers OffsetDateTime, not Instant
            params.put("day_start", OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC));
            params.put("day_end", OffsetDateTime.ofInstant(dayEnd, ZoneOffset.UTC));
        }
        return String.join(" and ", conditions);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
