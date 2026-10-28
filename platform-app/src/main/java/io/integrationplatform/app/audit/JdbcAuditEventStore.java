package io.integrationplatform.app.audit;

import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12 N3 (T-067 narrow): the append half of the audit ledger - the outbox writer and the relay for
 * {@code public.audit_event} (V2_2_1220), through the transactional outbox {@code public.audit_outbox} (V2_4_3,
 * row278 D2/M3).
 *
 * <p>Row 544: the application role no longer inserts into the ledger or stamps {@code relayed_at}; in
 * {@link RelayMode#DEFINER} the relay moves rows only through {@code public.audit_relay_move} (V2_4_1490).
 *
 * <p>R15: an append, never an update or a delete (the table grants refuse both; there is no UPDATE or DELETE
 * statement here). The tenant is taken only from {@link TenantContext} (R08) through the scoped base, and
 * the statement runs inside the caller's tenant-stamped transaction (R11) - the writer opens that transaction
 * fresh after commit. {@code target_ids} becomes a jsonb array of the event's target references (the delivery id
 * and pinned versions ride there for the operator actions - {@code AuditActions}).
 *
 * <p>The base's tenant predicate is satisfied by the {@code (values ...)} scoping row, the established idiom of
 * the parentless appends in {@code JdbcDeliveryRepository} (a row inserts exactly when the bound tenant matches
 * the caller-checked tenant, and RLS WITH CHECK is the second gate).
 */
final class JdbcAuditEventStore extends TenantScopedRepository implements AuditOutbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TenantContext tenantContext;
    private final RelayMode mode;

    JdbcAuditEventStore(JdbcClient jdbcClient, TenantContext tenantContext, RelayMode mode) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
        this.mode = mode;
    }

    /**
     * row278 D2/M3: appends the event as one pending {@code audit_outbox} row in the caller's transaction; exactly
     * one row or the transaction fails (and the business with it - the audit never lags a committed action).
     */
    @Override
    public void append(AuditEvent event) {
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("own_tenant", tenantContext.requireTenantId().toString());
        bound.put("actor", event.who());
        bound.put("action", event.action());
        bound.put("target_ids", JSON.writeValueAsString(event.targetIds() == null ? List.of() : event.targetIds()));
        bound.put("before_state", event.before());
        bound.put("after_state", event.after());
        // the repo-wide binding convention (JdbcDeliveryRepository.params): pgjdbc infers OffsetDateTime, not
        // Instant, so the event time is bound as its UTC offset
        bound.put("event_at", OffsetDateTime.ofInstant(event.at(), ZoneOffset.UTC));

        int rows = update(
                "insert into public.audit_outbox (tenant_id, actor, action, target_ids, before_state, after_state,"
                        + " event_at)"
                        + " select :own_tenant::uuid, :actor, :action, :target_ids::jsonb, :before_state,"
                        + " :after_state, :event_at"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null, bound);
        if (rows != 1) {
            throw new IllegalStateException("audit append for action " + event.action() + " wrote " + rows
                    + " rows (expected exactly one)");
        }
    }

    /**
     * Moves the bound tenant's oldest pending outbox rows into the append-only ledger. {@link RelayMode#DEFINER}
     * (row 544, plan D-2/D-3): a plain ordered pick (no {@code for update} - the role holds no UPDATE any more) and one
     * {@code audit_relay_move} call per picked id, all in this statement and the caller's tenant transaction; the
     * function takes the row lock ({@code skip locked}), re-checks the tenant and stamps + inserts atomically.
     * {@link RelayMode#LEGACY}: the previous single UPDATE+INSERT statement (needs the rollback SQL's grants).
     */
    @Override
    public int relayBound(int limit) {
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("own_tenant", tenantContext.requireTenantId().toString());
        bound.put("batch", limit);
        if (mode == RelayMode.LEGACY) {
            return update(
                    "with picked as (select id from public.audit_outbox where relayed_at is null"
                            + " and tenant_id = :own_tenant::uuid order by id limit :batch for update skip locked),"
                            + " moved as (update public.audit_outbox o set relayed_at = now() from picked"
                            + " where o.id = picked.id returning o.id, o.tenant_id, o.actor, o.action, o.target_ids,"
                            + " o.before_state, o.after_state, o.event_at, o.trace_id)"
                            + " insert into public.audit_event (tenant_id, actor, action, target_ids, before_state,"
                            + " after_state, event_at, trace_id)"
                            + " select tenant_id, actor, action, target_ids, before_state, after_state, event_at,"
                            + " trace_id from moved",
                    null, bound);
        }
        // the base appends "WHERE tenant_id = :tenant_id::uuid" to the final select (over the picked ids)
        return query(
                "with picked as materialized (select id, tenant_id from public.audit_outbox"
                        + " where relayed_at is null and tenant_id = :own_tenant::uuid order by id limit :batch)"
                        + " select public.audit_relay_move(id) from picked",
                null, bound, (rs, n) -> rs.getInt(1)).stream().mapToInt(Integer::intValue).sum();
    }
}
