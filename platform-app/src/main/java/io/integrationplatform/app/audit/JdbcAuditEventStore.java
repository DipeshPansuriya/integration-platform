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
 * E12 N3 (T-067 narrow): the append half of the audit ledger - the one writer of {@code public.audit_event}
 * (V2_2_1220). R15: an append, never an update or a delete (the table grants refuse both; there is no UPDATE or
 * DELETE statement here). The tenant is taken only from {@link TenantContext} (R08) through the scoped base, and
 * the statement runs inside the caller's tenant-stamped transaction (R11) - the writer opens that transaction
 * fresh after commit. {@code target_ids} becomes a jsonb array of the event's target references (the delivery id
 * and pinned versions ride there for the operator actions - {@code AuditActions}).
 *
 * <p>The base's tenant predicate is satisfied by the {@code (values ...)} scoping row, the established idiom of
 * the parentless appends in {@code JdbcDeliveryRepository} (a row inserts exactly when the bound tenant matches
 * the caller-checked tenant, and RLS WITH CHECK is the second gate).
 */
final class JdbcAuditEventStore extends TenantScopedRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TenantContext tenantContext;

    JdbcAuditEventStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
    }

    /** Appends the event as one immutable row; exactly one row or the transaction fails. */
    void append(AuditEvent event) {
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
                "insert into public.audit_event (tenant_id, actor, action, target_ids, before_state, after_state,"
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
}
