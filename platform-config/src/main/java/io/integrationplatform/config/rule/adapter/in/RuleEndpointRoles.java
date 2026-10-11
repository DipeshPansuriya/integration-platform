package io.integrationplatform.config.rule.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E6 (T-023, T-024): the rule endpoints and the {@code RolePolicy} operation the tenancy interceptor enforces for each.
 * Authoring is configuration editing; reading a rule set and running the side-effect-free test panel are reads.
 */
@Component
public class RuleEndpointRoles implements ApiOperationRules {

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("POST", RuleSetAdminController.FLOW_RULE_SETS, Operation.EDIT_CONFIGURATION),
                new Rule("GET", RuleSetAdminController.FLOW_RULE_SETS, Operation.READ_CONFIGURATION),
                new Rule("GET", RuleSetAdminController.RULE_SET, Operation.READ_CONFIGURATION),
                new Rule("PUT", RuleSetAdminController.RULE_SET, Operation.EDIT_CONFIGURATION),
                new Rule("POST", RuleSetAdminController.RULE_SET + "/evaluate", Operation.READ_CONFIGURATION));
    }
}
