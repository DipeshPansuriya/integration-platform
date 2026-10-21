package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.delivery.port.TargetHalfOpenCap;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E10 T96 / T-183: the {@link TargetHalfOpenCap} read from the target's own configuration - the
 * {@code halfOpenTrialCalls} key of its system's {@code settings} (SystemSettings, 1 to 100), the
 * {@value TargetHalfOpenCap#DEFAULT_TRIAL_CALLS} of ADR-011 when the key, the system or the target is absent or
 * unreadable. Single-table on {@code target_endpoint} (the system row is a scalar subquery on the same tenant), run in
 * the retry trigger's open tenant transaction.
 */
public final class JdbcTargetHalfOpenCap extends TenantScopedRepository implements TargetHalfOpenCap {

    private static final int MAX_TRIAL_CALLS = 100;

    public JdbcTargetHalfOpenCap(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public int trialCalls(UUID targetEndpointId) {
        List<String> configured = query(
                "select (select s.settings ->> 'halfOpenTrialCalls' from public.system s"
                        + " where s.tenant_id = te.tenant_id and s.id = te.system_id) from public.target_endpoint te",
                "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()), (rs, n) -> rs.getString(1));
        return configured.stream().findFirst().map(JdbcTargetHalfOpenCap::parse).orElse(DEFAULT_TRIAL_CALLS);
    }

    static int parse(String value) {
        if (value == null) {
            return DEFAULT_TRIAL_CALLS;
        }
        try {
            int calls = Integer.parseInt(value.trim());
            return calls >= 1 && calls <= MAX_TRIAL_CALLS ? calls : DEFAULT_TRIAL_CALLS;
        } catch (NumberFormatException notANumber) {
            return DEFAULT_TRIAL_CALLS;
        }
    }
}
