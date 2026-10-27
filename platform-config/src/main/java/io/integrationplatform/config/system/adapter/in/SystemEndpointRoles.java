package io.integrationplatform.config.system.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E5 T-102 / T-104: the role rule of each systems endpoint this lane adds (the E3 {@code POST /api/v1/admin/systems}
 * keeps its line in ConfigEndpointRoles - never named twice). Reading is READ_CONFIGURATION; editing and
 * test-connection (it reaches outside the platform) are EDIT_CONFIGURATION - configurators and tenant admins.
 */
@Component
public class SystemEndpointRoles implements ApiOperationRules {

    private static final String SYSTEMS = "/api/v1/admin/systems";
    private static final String ALLOWED_HOSTS = "/api/v1/admin/settings/allowed-hosts";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", SYSTEMS, Operation.READ_CONFIGURATION),
                new Rule("GET", SYSTEMS + "/{systemId}", Operation.READ_CONFIGURATION),
                new Rule("PUT", SYSTEMS + "/{systemId}", Operation.EDIT_CONFIGURATION),
                new Rule("POST", SYSTEMS + "/{systemId}/test-connection", Operation.EDIT_CONFIGURATION),
                // E16 F3: a configurator requests a DB table; only a tenant admin / approver approves or revokes one
                new Rule("GET", SYSTEMS + "/{systemId}/approved-tables", Operation.READ_CONFIGURATION),
                new Rule("POST", SYSTEMS + "/{systemId}/approved-tables", Operation.EDIT_CONFIGURATION),
                new Rule("POST", SYSTEMS + "/{systemId}/approved-tables/{id}/approve", Operation.APPROVE_DB_TABLE),
                new Rule("POST", SYSTEMS + "/{systemId}/approved-tables/{id}/revoke", Operation.APPROVE_DB_TABLE),
                new Rule("GET", ALLOWED_HOSTS, Operation.READ_CONFIGURATION),
                new Rule("POST", ALLOWED_HOSTS, Operation.EDIT_CONFIGURATION),
                new Rule("DELETE", ALLOWED_HOSTS, Operation.EDIT_CONFIGURATION));
    }
}
