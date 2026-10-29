package io.integrationplatform.config.destination.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tenant-scoped endpoint roles for the destination allow-list (Dipesh, 2026-10-29): READ_DESTINATIONS
 * and EDIT_DESTINATIONS are TENANT_ADMIN-only (Caller.ROLE). The operator endpoints keep their own
 * OPERATOR_AGENT operations unchanged ({@link OperatorAllowedHostEndpointRoles}).
 */
@Component
public class TenantAllowedHostEndpointRoles implements ApiOperationRules {

    private static final String DESTINATIONS = "/api/v1/admin/destinations";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", DESTINATIONS, Operation.READ_DESTINATIONS),
                new Rule("POST", DESTINATIONS, Operation.EDIT_DESTINATIONS),
                new Rule("DELETE", DESTINATIONS + "/{id}", Operation.EDIT_DESTINATIONS));
    }
}
