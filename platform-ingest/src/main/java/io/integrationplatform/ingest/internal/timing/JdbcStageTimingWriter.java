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
        List<Long> ids = query(RECORD, null, params, (rs, rowNum) -> rs.getLong("id"));
        if (ids.size() != 1) {
            throw new IllegalStateException("the stage timing row stored " + ids.size() + " rows for the bound"
                    + " tenant - the message is missing or the stamp disagreed (never expected); nothing recorded");
        }
    }
}