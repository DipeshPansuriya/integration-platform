package io.integrationplatform.config.flow.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Row 278 wave 2, T81 / B9: the role rule of the routes-listing read. Listing a flow's routes reads configuration,
 * exactly like {@code GET /api/v1/admin/flows/{flowId}} (ConfigEndpointRoles) - READ_CONFIGURATION, human callers
 * only; a source system's machine credential is refused (T-099).
 */
@Component
public class FlowRoutesEndpointRoles implements ApiOperationRules {

    static final String FLOW_ROUTES = FlowAdminController.PATH + "/{flowId}/routes";
    static final String ROUTE_CALLBACK = FLOW_ROUTES + "/{routeId}/callback";

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("GET", FLOW_ROUTES, Operation.READ_CONFIGURATION),
                new Rule("PATCH", ROUTE_CALLBACK, Operation.EDIT_CONFIGURATION));
    }
}
