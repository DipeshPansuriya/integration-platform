package io.integrationplatform.app.activity;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row327 T118 (E12-F2-S2, T-069): the READ half of the audit ledger ({@code public.audit_event}, V2_2_1220) for the
 * tenant activity view. Nothing here writes; {@code AuditEventWriter} stays the one writer.
 *
 * <p>Every statement runs through the tenant-scoped base (R08/R09/R11): the tenant comes only from
 * {@link TenantContext}, and every read sits inside the caller's tenant-stamped transaction, so row-level security is
 * the second gate. The page is the derived-table idiom of {@code JdbcDeliveryRetryStore.lockDue}: the inner select
 * carries the bound tenant, the filters, the newest-first order and the bound, the base adds its predicate outside.
 * SQL fragments are constants joined by {@code and}; every value is a named parameter (R11).
 */
final class JdbcActivityStore extends TenantScopedRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    /** What a row answers; {@code target_ids} leaves the database as its JSON text. */
    private static final String COLUMNS = "id, actor, action, target_ids::text as target_ids, before_state,"
            + " after_state, event_at, trace_id";

    /** One ledger row as the activity view shows it. */
    record ActivityRow(long id, String actor, String action, List<String> targetIds, String before, String after,
            Instant at, String traceId) {

        ActivityRow(long id, String actor, String action, List<String> targetIds, String before, String after,
                Instant at) {
            this(id, actor, action, targetIds, before, after, at, null);
        }
    }

    JdbcActivityStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /**
     * The bound tenant's events, newest first, optionally narrowed to one action and to {@code [from, toExclusive)},
     * at most {@code limit} rows after skipping {@code offset}.
     */
    List<ActivityRow> page(String action, Instant from, Instant toExclusive, int limit, long offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>(List.of("tenant_id = :tenant_id::uuid"));
        if (action != null) {
            conditions.add("action = :action");
            params.put("action", action);
        }
        if (from != null) {
            conditions.add("event_at >= :from_at");
            // the repo-wide binding convention: pgjdbc infers OffsetDateTime, not Instant
            params.put("from_at", OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        }
        if (toExclusive != null) {
            conditions.add("event_at < :to_at");
            params.put("to_at", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
        }
        params.put("limit", limit);
        params.put("offset", offset);
        return query("select " + COLUMNS + " from (select id, tenant_id, actor, action, target_ids, before_state,"
                        + " after_state, event_at, trace_id from public.audit_event where " + String.join(" and ", conditions)
                        + " order by event_at desc, id desc limit :limit offset :offset) scoped",
                null, params, JdbcActivityStore::row);
    }

    /** One of the bound tenant's events by id; empty when the tenant cannot see it. */
    Optional<ActivityRow> event(long id) {
        return query("select " + COLUMNS + " from public.audit_event", "id = :id", Map.of("id", id),
                JdbcActivityStore::row).stream().findFirst();
    }

    private static ActivityRow row(ResultSet rs, int n) throws SQLException {
        return new ActivityRow(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                JSON.readValue(rs.getString("target_ids"), STRINGS), rs.getString("before_state"),
                rs.getString("after_state"), rs.getObject("event_at", OffsetDateTime.class).toInstant(),
                rs.getString("trace_id"));
    }
}
