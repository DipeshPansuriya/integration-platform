package io.integrationplatform.ingest.internal.timing;

import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The append-only stage timing writer (E11 A1 narrow, T-048) - the {@link StageTimings} port over the
 * {@code message_stage_timing} family of the message store (ADR-014, R05/R08). The statement mirrors
 * {@code JdbcOutboxWriter}'s CTE shape: the INSERT joins the message row for the {@code accepted_at} partition
 * key, so a timing can never drift off the message's partition day, and the base's appended tenant predicate on
 * the RETURNING row is the guard that the timing landed on the bound tenant. A duplicated stage re-raises on the
 * primary key - a stage that says it ran twice is a bug, not an UPSERT.
 */
public class JdbcStageTimingWriter extends TenantScopedRepository implements StageTimings {

    private static final String RECORD = """
            with msg as (
                select m.id, m.tenant_id, m.accepted_at
                from public.message m
                where m.id = :message_id::uuid and m.tenant_id = :tenant_id::uuid
            ), ins as (
                insert into public.message_stage_timing
                    (message_id, tenant_id, accepted_at, stage, started_at, finished_at)
                select msg.id, msg.tenant_id, msg.accepted_at, :stage, :started_at, :finished_at from msg
                returning id, tenant_id
            )
            select ins.id, ins.tenant_id from ins""";

    /**
     * T105: the DELIVER row of the message a delivery belongs to, written by the final move that leaves none of the
     * message's deliveries open. Started at the message's first delivery attempt, but never before the TRANSFORM
     * stage finished (the attempt is clocked before the mapping runs inside it, so BS-09's agreed order INTAKE,
     * ROUTE, TRANSFORM, DELIVER needs the send itself as the start) and never after the finish; a row
     * already there (a replayed dead letter dying again) is a no-op through the one-row-per-stage UNIQUE. The caller
     * holds the message row lock already (every final move takes it first - T-165), so this statement's snapshot
     * sees every sibling that finished before it.
     */
    private static final String DELIVER_FINISHED = """
            with dlv as (
                select d.message_id
                from public.delivery d
                where d.id = :delivery_id::uuid and d.tenant_id = :tenant_id::uuid and d.message_id is not null
            ), msg as (
                select m.id, m.tenant_id, m.accepted_at
                from public.message m join dlv on dlv.message_id = m.id
                where m.tenant_id = :tenant_id::uuid
                and not exists (
                    select 1 from public.delivery s
                    where s.tenant_id = m.tenant_id and s.message_id = m.id
                    and s.state not in ('DELIVERED', 'DEAD_LETTERED'))
            ), first_call as (
                select min(a.started_at) as started_at
                from public.delivery_attempt a
                join public.delivery s on s.id = a.delivery_id and s.tenant_id = a.tenant_id
                join msg on msg.id = s.message_id
                where a.tenant_id = :tenant_id::uuid
            ), transformed as (
                select max(coalesce(t.finished_at, t.started_at)) as at
                from public.message_stage_timing t
                join msg on msg.id = t.message_id and msg.accepted_at = t.accepted_at
                where t.tenant_id = :tenant_id::uuid and t.stage = 'TRANSFORM'
            ), ins as (
                insert into public.message_stage_timing
                    (message_id, tenant_id, accepted_at, stage, started_at, finished_at)
                select msg.id, msg.tenant_id, msg.accepted_at, 'DELIVER',
                    least(greatest(coalesce(first_call.started_at, :finished_at::timestamptz),
                                   coalesce(transformed.at, first_call.started_at)),
                          :finished_at::timestamptz),
                    :finished_at::timestamptz
                from msg cross join first_call cross join transformed
                on conflict (message_id, accepted_at, stage) do nothing
                returning id, tenant_id
            )
            select ins.id, ins.tenant_id from ins""";

    public JdbcStageTimingWriter(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public void record(UUID messageId, Stage stage, Instant startedAt, Instant finishedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message_id", messageId.toString());
        params.put("stage", stage.name());
        params.put("started_at", Timestamp.from(startedAt));
        params.put("finished_at", finishedAt == null ? null : Timestamp.from(finishedAt));
        List<UUID> ids = query(RECORD, null, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.size() != 1) {
            throw new IllegalStateException("the stage timing row stored " + ids.size() + " rows for the bound"
                    + " tenant - the message is missing or the stamp disagreed (never expected); nothing recorded");
        }
    }

    @Override
    public void recordDeliverFinished(UUID deliveryId, Instant finishedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("delivery_id", deliveryId.toString());
        params.put("finished_at", Timestamp.from(finishedAt));
        // 0 rows is the documented no-op (an open sibling, no stage-1 message, a row already written, another
        // tenant's delivery): the delivery's own move must never fail on its timing
        query(DELIVER_FINISHED, null, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }
}