package io.integrationplatform.routing.callback;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and updates {@code delivery_response} rows for the callback engine. Queries use
 * {@code FOR UPDATE SKIP LOCKED} so multiple scheduler instances or consumer threads never
 * process the same row. Every statement runs through the tenant-scoped base (R08).
 */
public class JdbcCallbackStore extends TenantScopedRepository {

    private static final int MAX_ATTEMPTS = 5;

    private static final String SELECT_PENDING =
            "SELECT scoped.id, scoped.tenant_id, scoped.delivery_id, scoped.callback_url,"
                    + " scoped.callback_mode, scoped.status, scoped.content_type, scoped.body, scoped.attempts"
                    + " FROM ("
                    + " SELECT dr.id, dr.tenant_id, dr.delivery_id, r.callback_url, r.callback_mode,"
                    + " dr.status, dr.content_type, dr.body, dr.attempts"
                    + " FROM public.delivery_response dr"
                    + " JOIN public.delivery d ON d.id = dr.delivery_id AND d.tenant_id = dr.tenant_id"
                    + " JOIN public.route r ON r.id = d.route_id AND r.tenant_id = d.tenant_id"
                    + " WHERE dr.tenant_id = :tenant_id::uuid"
                    + " AND dr.callback_state IN ('PENDING', 'RETRY')"
                    + " AND r.callback_mode = :mode"
                    + " ORDER BY dr.captured_at"
                    + " LIMIT :batch_size"
                    + " FOR UPDATE OF dr SKIP LOCKED) scoped";

    private static final String MARK_SENT =
            "UPDATE public.delivery_response"
                    + " SET callback_state = 'SENT', attempts = attempts + 1, updated_at = now()";

    private static final String MARK_RETRY =
            "UPDATE public.delivery_response"
                    + " SET callback_state = CASE WHEN attempts + 1 >= :max_attempts THEN 'DEAD' ELSE 'RETRY' END,"
                    + " attempts = attempts + 1, updated_at = now()";

    public JdbcCallbackStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    public List<CallbackRecord> claimPending(String mode, int batchSize) {
        return query(SELECT_PENDING, null,
                Map.of("mode", mode, "batch_size", batchSize),
                (rs, n) -> new CallbackRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("delivery_id", UUID.class),
                        rs.getString("callback_url"),
                        rs.getString("callback_mode"),
                        rs.getInt("status"),
                        rs.getString("content_type"),
                        rs.getBytes("body"),
                        rs.getInt("attempts")));
    }

    public void markSent(UUID responseId) {
        update(MARK_SENT, "id = :id::uuid",
                Map.of("id", responseId.toString()));
    }

    public void markFailed(UUID responseId) {
        update(MARK_RETRY, "id = :id::uuid",
                Map.of("id", responseId.toString(), "max_attempts", MAX_ATTEMPTS));
    }
}
