package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E16 F3 (V2_4_1330): the approved tables of a DB target system. The table is under row-level security and the app
 * role has SELECT and INSERT only - a request is an insert here; approving or revoking runs in the
 * {@code config_decide_db_table_approval} definer function (the V2_2_5_1 pattern), which takes the tenant from the
 * stamped session setting and answers by code. Every statement keeps the base's tenant predicate.
 */
public final class JdbcApprovedTableRepository extends TenantScopedRepository {

    /** One row; {@code decidedBy}/{@code decidedAt} are empty until approved. */
    public record ApprovedTableRow(String id, String systemId, String tableName, String status, String requestedBy,
            Instant requestedAt, String decidedBy, Instant decidedAt, boolean targetCurrent) {
    }

    private static final RowMapper<ApprovedTableRow> ROW = (rs, n) -> new ApprovedTableRow(
            rs.getString("id"), rs.getString("system_id"), rs.getString("table_name"), rs.getString("status"),
            rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("decided_by"),
            instant(rs, "decided_at"), rs.getBoolean("target_current"));

    private static final String READ = "select a.id::text as id, a.system_id::text as system_id, a.table_name,"
            + " a.status, a.requested_by, a.requested_at, a.decided_by, a.decided_at,"
            + " coalesce((select a.target_hash = public.db_target_fingerprint(s.settings) from public.system s"
            + " where s.id = a.system_id), false) as target_current"
            + " from public.db_target_approved_table a";

    private static final String INSERT_REQUEST = "with ins as ("
            + "insert into public.db_target_approved_table (tenant_id, system_id, table_name, status, requested_by,"
            + " target_hash)"
            + " values (current_setting('app.tenant_id')::uuid, :system_id::uuid, :table_name, 'PENDING_APPROVAL',"
            + " :requested_by, (select public.db_target_fingerprint(s.settings) from public.system s"
            + " where s.id = :system_id::uuid)) returning id, tenant_id) select id::text from ins";

    /** The definer decision (V2_4_1330); the tenant column keeps the base's predicate satisfiable. */
    private static final String DECIDE = "select result from ("
            + "    select public.config_decide_db_table_approval(:id::uuid, :decision) as result,"
            + "    current_setting('app.tenant_id')::uuid as tenant_id) called";

    /** Karan change 2: the acting user as a transaction-local setting, read only by the definer function. */
    private static final String STAMP_ACTOR = "select stamped from ("
            + "    select set_config('app.actor_id', :decider, true) as stamped,"
            + "    current_setting('app.tenant_id')::uuid as tenant_id) stamp";

    public JdbcApprovedTableRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    /** The system's rows, oldest request first. */
    public List<ApprovedTableRow> list(String systemId) {
        return query(READ, "a.system_id = :system_id::uuid", Params.of("system_id", systemId), ROW).stream()
                .sorted(java.util.Comparator.comparing(ApprovedTableRow::requestedAt)
                        .thenComparing(ApprovedTableRow::tableName))
                .toList();
    }

    public Optional<ApprovedTableRow> find(String id) {
        return query(READ, "a.id = :id::uuid", Params.of("id", id), ROW).stream().findFirst();
    }

    /**
     * Inserts a PENDING_APPROVAL request and answers its id.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the table is already pending or approved
     */
    public String insertRequest(String systemId, String tableName, String requestedBy) {
        return query(INSERT_REQUEST, null,
                Params.of("system_id", systemId, "table_name", tableName, "requested_by", requestedBy),
                (RowMapper<String>) (rs, n) -> rs.getString(1)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("the approved-table insert returned no row"));
    }

    /**
     * The definer decision's answer: {@code APPROVED}, {@code REVOKED}, {@code NOT_FOUND},
     * {@code REFUSED_SELF_APPROVAL}, {@code REFUSED_TARGET_CHANGED}, {@code REFUSED_UNKNOWN_DECISION} or
     * {@code REFUSED_<status>}. The decider is stamped into the transaction first (never a function argument).
     *
     * @param decision {@code APPROVE} or {@code REVOKE}
     */
    public String decide(String id, String deciderId, String decision) {
        query(STAMP_ACTOR, null, Params.of("decider", java.util.UUID.fromString(deciderId).toString()),
                (RowMapper<String>) (rs, n) -> rs.getString(1));
        return query(DECIDE, null, Params.of("id", id, "decision", decision),
                (RowMapper<String>) (rs, n) -> rs.getString(1)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("the approved-table decision returned no row"));
    }
}
