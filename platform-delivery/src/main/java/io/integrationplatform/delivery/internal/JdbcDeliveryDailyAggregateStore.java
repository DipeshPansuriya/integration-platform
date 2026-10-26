package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-138: {@link DeliveryDailyAggregates} over delivery + delivery_attempt. The direct count groups inside a subquery,
 * so the tenant predicate {@link TenantScopedRepository} appends lands on the outer select; FORCE RLS is the second
 * fence.
 */
public class JdbcDeliveryDailyAggregateStore extends TenantScopedRepository implements DeliveryDailyAggregates {

    private static final String COUNTS = "successes, failures_transient, failures_ambiguous, failures_permanent,"
            + " retries_made, retried_then_succeeded, still_stuck";

    private static final String DIRECT = "(select d.tenant_id, d.interface_id, d.target_id,"
            + " count(*) filter (where d.state = 'DELIVERED') successes,"
            + " count(*) filter (where d.state = 'DEAD_LETTERED' and d.dead_letter_class = 'TRANSIENT')"
            + " failures_transient,"
            + " count(*) filter (where d.state = 'DEAD_LETTERED' and d.dead_letter_class = 'AMBIGUOUS')"
            + " failures_ambiguous,"
            + " count(*) filter (where d.state = 'DEAD_LETTERED' and d.dead_letter_class = 'PERMANENT')"
            + " failures_permanent,"
            + " coalesce(sum(greatest(a.n - 1, 0)), 0)::bigint retries_made,"
            + " count(*) filter (where d.state = 'DELIVERED' and a.n > 1) retried_then_succeeded,"
            + " count(*) filter (where d.state not in ('DELIVERED', 'DEAD_LETTERED')) still_stuck"
            + " from public.delivery d"
            + " left join lateral (select count(*) n from public.delivery_attempt x"
            + " where x.tenant_id = d.tenant_id and x.delivery_id = d.id) a on true"
            + " where d.created_at >= :from and d.created_at < :to"
            + " group by d.tenant_id, d.interface_id, d.target_id) scoped";

    private static final RowMapper<DailyCounts> ROW = (rs, n) -> new DailyCounts(
            rs.getObject("interface_id", UUID.class), rs.getObject("target_id", UUID.class), rs.getLong("successes"),
            rs.getLong("failures_transient"), rs.getLong("failures_ambiguous"), rs.getLong("failures_permanent"),
            rs.getLong("retries_made"), rs.getLong("retried_then_succeeded"), rs.getLong("still_stuck"));

    public JdbcDeliveryDailyAggregateStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public int refresh(LocalDate day) {
        Map<String, Object> p = params(day);
        update("delete from public.delivery_daily_aggregate", "day = :day", p);
        return update("insert into public.delivery_daily_aggregate (tenant_id, day, interface_id, target_id, " + COUNTS
                + ") select tenant_id, :day, interface_id, target_id, " + COUNTS + " from " + DIRECT, null, p);
    }

    @Override
    public List<DailyCounts> aggregated(LocalDate day) {
        return query("select * from public.delivery_daily_aggregate", "day = :day", params(day), ROW);
    }

    @Override
    public List<DailyCounts> direct(LocalDate day) {
        return query("select * from " + DIRECT, null, params(day), ROW);
    }

    private static Map<String, Object> params(LocalDate day) {
        return Map.of("day", day, "from", day.atStartOfDay().atOffset(ZoneOffset.UTC),
                "to", day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }
}
