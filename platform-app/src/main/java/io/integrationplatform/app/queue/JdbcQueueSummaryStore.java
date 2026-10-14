package io.integrationplatform.app.queue;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F10-narrow (row278 release-base plan step 8): READ-ONLY reads of the stage state the queue dashboard counts -
 * the E8 lane-A message store ({@code public.message}) and the E2 delivery state machine ({@code public.delivery}),
 * plus flow labels from {@code public.interface}. Nothing here writes; the tables' owners (platform-routing /
 * platform-delivery) keep their state machines.
 *
 * <p>Every statement runs through the tenant-scoped base (R08/R09/R11): the tenant predicate is the base's, the
 * tenant comes only from {@link TenantContext}, and every read sits inside the caller's tenant-stamped transaction
 * (the service's {@code @Transactional(readOnly = true)}), so row-level security fails closed for an unbound read.
 *
 * <p>Day bucketing is the UTC day of {@code accepted_at} / {@code created_at} (delivery.created_at IS the message's
 * accepted_at per ADR-014, so the two tables share one day axis). The counts themselves are grouped in the service
 * - the narrow scope derives the four figures live from the state register, no aggregate maintenance.
 */
final class JdbcQueueSummaryStore extends TenantScopedRepository {

    /** One stage row: its flow, its closed-set state (R10 text + CHECK in the tables) and its UTC day. */
    record StageRow(String flowId, String state, String day) {
    }

    /** A flow's label row: every figure the dashboard shows is grouped into this label. */
    record FlowLabel(String id, String code, String name) {
    }

    JdbcQueueSummaryStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The message stage rows (E8 lane-A store), optionally narrowed to one flow and one UTC day. */
    List<StageRow> messageRows(UUID flowId, LocalDate day) {
        return dayRows("select interface_id, state, to_char(accepted_at at time zone 'UTC', 'YYYY-MM-DD') as day"
                        + ", tenant_id from public.message",
                "accepted_at at time zone 'UTC'", flowId, day, mapper());
    }

    /** The delivery stage rows (E2 state machine), same narrowing. */
    List<StageRow> deliveryRows(UUID flowId, LocalDate day) {
        return dayRows("select interface_id, state, to_char(created_at at time zone 'UTC', 'YYYY-MM-DD') as day"
                        + ", tenant_id from public.delivery",
                "created_at at time zone 'UTC'", flowId, day, mapper());
    }

    /** The tenant's flows as labels (code/name), in code order - the dashboard groups into these. */
    List<FlowLabel> flowLabels() {
        return query("select id::text as id, code, name from public.interface", null, Map.of(),
                (rs, n) -> new FlowLabel(rs.getString("id"), rs.getString("code"), rs.getString("name")))
                .stream().sorted(java.util.Comparator.comparing(FlowLabel::code)).toList();
    }

    private List<StageRow> dayRows(String selectFrom, String timeExpression, UUID flowId, LocalDate day,
            RowMapper<StageRow> mapper) {
        List<String> conditions = new java.util.ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (flowId != null) {
            conditions.add("interface_id = :flow_id::uuid");
            params.put("flow_id", flowId.toString());
        }
        if (day != null) {
            conditions.add("to_char(" + timeExpression + ", 'YYYY-MM-DD') = :day");
            params.put("day", day.toString());
        }
        String condition = conditions.isEmpty() ? null : String.join(" and ", conditions);
        return query(selectFrom, condition, params, mapper);
    }

    private static RowMapper<StageRow> mapper() {
        return (rs, n) -> new StageRow(rs.getString("interface_id"), rs.getString("state"), rs.getString("day"));
    }
}