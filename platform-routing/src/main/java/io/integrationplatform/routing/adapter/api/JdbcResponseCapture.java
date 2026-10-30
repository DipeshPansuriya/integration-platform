package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.routing.callback.CallbackImmediateNotifier;
import io.integrationplatform.routing.stage2.ResponseCapturePort;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Persists the target's HTTP response into {@code delivery_response} (V2_4_1560) so the callback
 * engine can forward it to the originating system. The insert determines {@code callback_state}
 * from the route's {@code callback_mode}: PENDING when configured, CAPTURED otherwise. When the
 * mode is IMMEDIATE the notifier fires an asynchronous dispatch so the callback reaches the
 * originating system within seconds. A failure to capture is logged but never fails the delivery
 * itself — the response is a bonus for callbacks, not a delivery prerequisite.
 */
public class JdbcResponseCapture extends TenantScopedRepository implements ResponseCapturePort {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcResponseCapture.class);

    private static final String INSERT =
            "INSERT INTO public.delivery_response (id, tenant_id, delivery_id, status, content_type, body, callback_state)"
                    + " SELECT :id::uuid, scoped.tenant_id, scoped.delivery_id, :status, :content_type, :body,"
                    + " scoped.callback_state FROM ("
                    + " SELECT d.tenant_id, d.id AS delivery_id,"
                    + " CASE WHEN r.callback_mode IS NOT NULL THEN 'PENDING' ELSE 'CAPTURED' END AS callback_state"
                    + " FROM public.delivery d"
                    + " LEFT JOIN public.route r ON r.id = d.route_id AND r.tenant_id = d.tenant_id"
                    + " WHERE d.tenant_id = :tenant_id::uuid AND d.id = :delivery_id::uuid) scoped";

    private static final String CALLBACK_MODE =
            "SELECT scoped.callback_mode FROM ("
                    + " SELECT r.callback_mode, d.tenant_id"
                    + " FROM public.delivery d"
                    + " JOIN public.route r ON r.id = d.route_id AND r.tenant_id = d.tenant_id"
                    + " WHERE d.tenant_id = :tenant_id::uuid AND d.id = :delivery_id::uuid) scoped";

    private final TenantContext tenantContext;
    private final CallbackImmediateNotifier immediateNotifier;

    public JdbcResponseCapture(JdbcClient jdbcClient, TenantContext tenantContext) {
        this(jdbcClient, tenantContext, null);
    }

    public JdbcResponseCapture(JdbcClient jdbcClient, TenantContext tenantContext,
            CallbackImmediateNotifier immediateNotifier) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
        this.immediateNotifier = immediateNotifier;
    }

    @Override
    public void capture(UUID deliveryId, int status, String contentType, byte[] body) {
        try {
            UUID tenantId = tenantContext.requireTenantId();
            int rows = update(INSERT, null,
                    params("id", UUID.randomUUID().toString(),
                            "delivery_id", deliveryId.toString(),
                            "status", status,
                            "content_type", contentType,
                            "body", body));

            if (rows > 0 && immediateNotifier != null) {
                notifyIfImmediate(deliveryId, tenantId);
            }
        } catch (Exception e) {
            LOG.atWarn().addKeyValue("event", "response-capture.failed")
                    .addKeyValue("deliveryId", deliveryId)
                    .setCause(e)
                    .log("failed to capture target response for delivery {}; delivery itself succeeded", deliveryId);
        }
    }

    private void notifyIfImmediate(UUID deliveryId, UUID tenantId) {
        try {
            var modes = query(CALLBACK_MODE, null,
                    params("delivery_id", deliveryId.toString()),
                    (rs, n) -> rs.getString("callback_mode"));
            String mode = modes.isEmpty() ? null : modes.getFirst();
            if ("IMMEDIATE".equals(mode)) {
                immediateNotifier.fire(deliveryId, tenantId);
            }
        } catch (Exception e) {
            LOG.atWarn().addKeyValue("event", "callback.immediate-notify.failed")
                    .addKeyValue("deliveryId", deliveryId)
                    .setCause(e)
                    .log("failed to check/notify IMMEDIATE callback for delivery {}; the SCHEDULED job will pick it up",
                            deliveryId);
        }
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
