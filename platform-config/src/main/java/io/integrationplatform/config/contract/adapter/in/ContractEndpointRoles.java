package io.integrationplatform.config.contract.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E5 lane A (T-105): the {@code RolePolicy} operation of every contract endpoint this lane adds (E4 T-099). The three
 * E3 contract endpoints keep their lines in {@code ConfigEndpointRoles}; no method + pattern is named twice.
 */
@Component
public class ContractEndpointRoles implements ApiOperationRules {

    private static final String CONTRACTS = "/api/v1/admin/contracts";
    private static final String VERSIONS = CONTRACTS + "/versions";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("POST", VERSIONS + "/import", Operation.EDIT_CONFIGURATION),
                new Rule("GET", CONTRACTS, Operation.READ_CONFIGURATION),
                new Rule("GET", CONTRACTS + "/{contractId}/versions", Operation.READ_CONFIGURATION),
                new Rule("POST", VERSIONS + "/{versionId}/approve", Operation.APPROVE_VERSION));
    }
}
