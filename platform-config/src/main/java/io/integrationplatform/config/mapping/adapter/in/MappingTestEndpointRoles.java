package io.integrationplatform.config.mapping.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E7 lane B2: the role rule of the mapping test endpoint. Testing a mapping runs the mapping's stored expression
 * against configuration that only a configurator ever changes and records the sample run that later feeds the
 * publish gate - it is EDIT_CONFIGURATION, the operation the mapping save already carries (MappingEndpointRoles).
 */
@Component
public class MappingTestEndpointRoles implements ApiOperationRules {

    static final String MAPPING_TEST = "/api/v1/admin/mappings/{id}/test";

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("POST", MAPPING_TEST, Operation.EDIT_CONFIGURATION));
    }
}