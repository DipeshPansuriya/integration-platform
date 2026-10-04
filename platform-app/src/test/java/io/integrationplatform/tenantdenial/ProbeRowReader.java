package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only tenant-scoped reader of the probe_row fixture: the first wall is the predicate on the bound tenant from
 * {@link TenantContext} (R08, explicit {@code ::uuid} cast R09), the second is row-level security under the
 * per-transaction {@code app.tenant_id} stamp (lane C). Every read runs in a transaction (R11) and is counted, so the
 * suite can prove that a rejected request read no tenant data.
 *
 * <p>{@code @TestComponent}: never component-scanned; only the denial suite imports it.
 */
@TestComponent
public class ProbeRowReader {

    private static final String COLUMNS = "SELECT id, tenant_id, label FROM probe_row WHERE tenant_id = :tenant::uuid";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<TenantContext> tenantContext;
    private final AtomicInteger reads = new AtomicInteger();

    public ProbeRowReader(NamedParameterJdbcTemplate jdbc, ObjectProvider<TenantContext> tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Transactional(readOnly = true)
    public List<ProbeRow> all() {
        return query(COLUMNS + " ORDER BY label", params());
    }

    @Transactional(readOnly = true)
    public Optional<ProbeRow> byId(UUID id) {
        return query(COLUMNS + " AND id = :id::uuid", params().addValue("id", id.toString())).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<ProbeRow> byLabelPrefix(String prefix) {
        String like = (prefix == null ? "" : prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return query(COLUMNS + " AND label LIKE :like ORDER BY label", params().addValue("like", like));
    }

    /** Statements sent to the database since the last reset. */
    public int reads() {
        return reads.get();
    }

    public void resetReads() {
        reads.set(0);
    }

    private MapSqlParameterSource params() {
        // Fails closed (NoTenantBoundException) when nothing is bound, before any statement is sent.
        TenantContext context = tenantContext.getIfAvailable();
        if (context == null) {
            throw new NoTenantBoundException();
        }
        return new MapSqlParameterSource("tenant", context.requireTenantId().toString());
    }

    private List<ProbeRow> query(String sql, MapSqlParameterSource params) {
        reads.incrementAndGet();
        return jdbc.query(sql, params, (rs, n) -> new ProbeRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("label")));
    }

    /** One probe row as the API returns it. */
    public record ProbeRow(UUID id, UUID tenantId, String label) {
    }
}
