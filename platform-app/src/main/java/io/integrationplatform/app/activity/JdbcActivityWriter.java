package io.integrationplatform.app.activity;

import io.integrationplatform.audit.api.ActivityEntry;
import io.integrationplatform.audit.api.ActivityWriteApi;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12 write side (T-043 / T-069): appends one activity event to the audit OUTBOX ({@code public.audit_outbox},
 * V2_4_3 + {@code trace_id}, V2_4_1490) in the CALLER's tenant-stamped transaction, so it commits or rolls back
 * with the business action. Row 544 (Q1): the application role can no longer insert into the ledger
 * ({@code public.audit_event}) itself; the relay moves the row there through {@code audit_relay_move}, so an activity
 * entry is readable from the ledger once the relay has run (no production caller exists today). The
 * tenant is taken only from {@link TenantContext} (R08): an entry names none, and with none bound this throws
 * {@code NoTenantBoundException} before any SQL runs. The row inserts through the parentless-append idiom of
 * {@code JdbcAuditEventStore} (a {@code values} scoping row the base's predicate filters; RLS WITH CHECK is the
 * second gate). INSERT only - the table grants refuse UPDATE and DELETE (R15) and no such statement exists here.
 */
public final class JdbcActivityWriter extends TenantScopedRepository implements ActivityWriteApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TenantContext tenantContext;

    public JdbcActivityWriter(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
    }

    @Override
    public void record(ActivityEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("own_tenant", tenantContext.requireTenantId().toString());
        bound.put("actor", entry.actor());
        bound.put("action", entry.action());
        bound.put("target_ids", JSON.writeValueAsString(entry.targetIds()));
        bound.put("before_state", entry.before());
        bound.put("after_state", entry.after());
        // the repo-wide binding convention: pgjdbc infers OffsetDateTime, not Instant
        bound.put("event_at", OffsetDateTime.ofInstant(entry.at(), ZoneOffset.UTC));
        bound.put("trace_id", entry.traceId());

        int rows = update(
                "insert into public.audit_outbox (tenant_id, actor, action, target_ids, before_state, after_state,"
                        + " event_at, trace_id)"
                        + " select :own_tenant::uuid, :actor, :action, :target_ids::jsonb, :before_state,"
                        + " :after_state, :event_at, :trace_id"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null, bound);
        if (rows != 1) {
            throw new IllegalStateException("activity record for action " + entry.action() + " wrote " + rows
                    + " rows (expected exactly one)");
        }
    }
}
