package io.integrationplatform.routing.compose;

import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkKind;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.port.WorkTenantLookup;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The stage consumers' protected path (ADR-011 Decision 8; mahesh-architect's ruling m-6f7aa7 - "port =
 * StageConsumer.ProtectedRows, SQL-free adapter, the crossing inside platform-tenancy"): the row's tenant comes from
 * tenancy's reviewed {@code ipaas_work_tenant} crossing, never from the record; the visibility check then runs as that
 * tenant, tenant-stamped and under row-level security, so a row the bound tenant cannot see is never worked on.
 */
public final class ProtectedWorkRows implements StageConsumer.ProtectedRows {

    private final WorkTenantLookup lookup;
    private final WorkerTenantBinding binding;
    private final TransactionOperations transactions;
    private final Visibility visibility;

    public ProtectedWorkRows(WorkTenantLookup lookup, WorkerTenantBinding binding,
            TransactionOperations transactions, JdbcClient jdbcClient, TenantContext tenantContext) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.visibility = new Visibility(jdbcClient, tenantContext);
    }

    @Override
    public UUID tenantFor(WorkKind kind, UUID id) {
        return lookup.tenantOf(kind.name(), id);
    }

    @Override
    public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
        return binding.callAs(tenantId, () -> Boolean.TRUE.equals(
                transactions.execute(status -> visibility.visible(kind, id))));
    }

    /** The tenant-scoped existence check: one table per kind, the tenant predicate appended (R08). */
    static final class Visibility extends TenantScopedRepository {

        Visibility(JdbcClient jdbcClient, TenantContext tenantContext) {
            super(jdbcClient, tenantContext);
        }

        boolean visible(WorkKind kind, UUID id) {
            String selectFrom = kind == WorkKind.MESSAGE
                    ? "select m.id from public.message m"
                    : "select d.id from public.delivery d";
            String condition = kind == WorkKind.MESSAGE ? "m.id = :id::uuid" : "d.id = :id::uuid";
            return !query(selectFrom, condition, Map.of("id", id.toString()), (rs, n) -> Boolean.TRUE).isEmpty();
        }
    }
}
