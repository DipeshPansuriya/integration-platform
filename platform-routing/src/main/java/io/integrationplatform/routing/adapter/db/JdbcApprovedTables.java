package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E16 F3 over {@code public.db_target_approved_table} (V2_4_1330, forced row-level security): the send-time and
 * publish-time reads. An approval counts only while its target fingerprint equals the CURRENT fingerprint of the
 * system's settings (host, port, database, account). Each statement carries the base's tenant predicate; any other
 * table is reached by a sub-select so {@code tenant_id} stays unambiguous. A database error propagates - never
 * "allowed".
 */
public class JdbcApprovedTables extends TenantScopedRepository implements ApprovedTables, UnapprovedTables {

    private static final String SELECT_APPROVED = "select 1 as v from public.db_target_approved_table a";

    private static final String APPROVED_FOR_ENDPOINT = "a.status = 'APPROVED'"
            + " and lower(a.table_name) = lower(:table)"
            + " and a.system_id = (select te.system_id from public.target_endpoint te where te.id = :endpoint_id::uuid)"
            + " and a.target_hash = public.db_target_fingerprint("
            + "(select s.settings from public.system s where s.id = a.system_id))";

    /** The DB tables of the interface's active routes with no live approval bound to the current target. */
    private static final String SELECT_UNAPPROVED = """
            select (select s.code from public.target_endpoint te join public.system s on s.id = te.system_id
                     where te.id = r.target_endpoint_id) || '.' ||
                   (select s.settings ->> 'table' from public.target_endpoint te join public.system s on s.id = te.system_id
                     where te.id = r.target_endpoint_id) as v
            from public.route r""";

    private static final String UNAPPROVED_FOR_INTERFACE = "r.interface_id = :interface_id::uuid and r.status = 'ACTIVE'"
            + " and exists (select 1 from public.target_endpoint te join public.system s on s.id = te.system_id"
            + "   where te.id = r.target_endpoint_id and s.type = 'DB' and s.settings ->> 'table' is not null"
            + "   and not exists (select 1 from public.db_target_approved_table a where a.system_id = s.id"
            + "     and a.status = 'APPROVED' and lower(a.table_name) = lower(s.settings ->> 'table')"
            + "     and a.target_hash = public.db_target_fingerprint(s.settings)))";

    public JdbcApprovedTables(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public boolean isApproved(UUID targetEndpointId, String table) {
        return !query(SELECT_APPROVED, APPROVED_FOR_ENDPOINT,
                Map.of("endpoint_id", targetEndpointId.toString(), "table", table), (rs, n) -> rs.getInt("v"))
                .isEmpty();
    }

    @Override
    public List<String> ofInterface(UUID interfaceId) {
        return query(SELECT_UNAPPROVED, UNAPPROVED_FOR_INTERFACE, Map.of("interface_id", interfaceId.toString()),
                (rs, n) -> rs.getString("v"));
    }
}
