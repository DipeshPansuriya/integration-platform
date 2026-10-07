package io.integrationplatform.config.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E4 T-099: every config admin endpoint (method + mapping pattern) and the {@code RolePolicy} operation the tenancy
 * module's interceptor enforces for it - server-side, for the bearer and the console session chain alike. The E3
 * controllers carry no role code and stay as they are; a new config endpoint without a line here fails platform-app's
 * {@code ApiOperationRulesCompletenessTest}.
 */
@Component
public class ConfigEndpointRoles implements ApiOperationRules {

    private static final String SYSTEMS = "/api/v1/admin/systems";
    private static final String FLOWS = "/api/v1/admin/flows";
    private static final String CONTRACT_VERSIONS = "/api/v1/admin/contracts/versions";

    @Override
    public List<Rule> rules() {
        return List.of(
                // systems (T-015): registering a system is configuration editing
                new Rule("POST", SYSTEMS, Operation.EDIT_CONFIGURATION),
                // contracts (T-019)
                new Rule("POST", CONTRACT_VERSIONS, Operation.EDIT_CONFIGURATION),
                new Rule("GET", CONTRACT_VERSIONS + "/{versionId}", Operation.READ_CONFIGURATION),
                new Rule("POST", CONTRACT_VERSIONS + "/{versionId}/submit", Operation.SUBMIT_FOR_APPROVAL),
                // flows (T-015, T-019, T-021)
                new Rule("GET", FLOWS, Operation.READ_CONFIGURATION),
                new Rule("POST", FLOWS, Operation.EDIT_CONFIGURATION),
                new Rule("GET", FLOWS + "/{flowId}", Operation.READ_CONFIGURATION),
                new Rule("PUT", FLOWS + "/{flowId}", Operation.EDIT_CONFIGURATION),
                // the in-place version edit is always refused (409); still an edit attempt, so editors only
                new Rule("PUT", FLOWS + "/{flowId}/versions/{versionId}", Operation.EDIT_CONFIGURATION),
                new Rule("POST", FLOWS + "/{flowId}/submit", Operation.SUBMIT_FOR_APPROVAL),
                new Rule("POST", FLOWS + "/{flowId}/approve", Operation.APPROVE_VERSION),
                new Rule("POST", FLOWS + "/{flowId}/publish", Operation.PUBLISH_VERSION));
    }
}
