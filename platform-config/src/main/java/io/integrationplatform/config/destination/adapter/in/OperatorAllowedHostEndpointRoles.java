package io.integrationplatform.config.destination.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Row 578 (CWE-862 / CWE-918): the destination allow-list is one global, operator-owned list, so every route is an
 * OPERATOR_AGENT operation - no tenant role (read-only included) and no source credential reaches it.
 */
@Component
public class OperatorAllowedHostEndpointRoles implements ApiOperationRules {

    private static final String HOSTS = OperatorAllowedHostController.PATH;

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", HOSTS, Operation.READ_ALLOWED_HOSTS),
                new Rule("POST", HOSTS, Operation.EDIT_ALLOWED_HOSTS),
                new Rule("DELETE", HOSTS + "/{id}", Operation.EDIT_ALLOWED_HOSTS));
    }
}
