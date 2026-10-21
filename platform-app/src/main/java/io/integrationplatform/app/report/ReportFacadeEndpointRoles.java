package io.integrationplatform.app.report;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Row327 T-139 analogue: report facade is an operational read of the tenant's own tracking/artivity,
 * so it rides READ_MESSAGE_TIMELINE like the delivery report and activity endpoint.
 */
@Component
public class ReportFacadeEndpointRoles implements ApiOperationRules {

    static final String FACADE = "/api/v1/reports/facade/{trackingId}";

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("GET", FACADE, Operation.READ_MESSAGE_TIMELINE));
    }
}
EOF