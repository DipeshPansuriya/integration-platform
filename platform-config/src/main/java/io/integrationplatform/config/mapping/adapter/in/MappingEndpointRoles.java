package io.integrationplatform.config.mapping.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E7 T-190: the role rule of the mapping canvas's endpoints on a route. Reading a route's mapping back
 * (the canvas re-renders exactly as left) is READ_CONFIGURATION, like the rest of a configuration read;
 * saving it - the field mappings, the compiled expression and the source/target contract-version bindings
 * - is EDIT_CONFIGURATION, so only the configurator role chain that edits configuration reaches it.
 */
@Component
public class MappingEndpointRoles implements ApiOperationRules {

    static final String MAPPING = "/api/v1/admin/routes/{routeId}/mapping";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", MAPPING, Operation.READ_CONFIGURATION),
                new Rule("PUT", MAPPING, Operation.EDIT_CONFIGURATION));
    }
}
