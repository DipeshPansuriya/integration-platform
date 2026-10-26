package io.integrationplatform.delivery.report;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Row327 T-139: the delivery report is an operational read of the tenant's own deliveries, so it rides
 * READ_MESSAGE_TIMELINE (tenant admin, support, read-only; never the agent) like ActivityEndpointRoles.
 */
@Component
public class DeliveryReportEndpointRoles implements ApiOperationRules {

    static final String REPORT = "/api/v1/reports/delivery";

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("GET", REPORT, Operation.READ_MESSAGE_TIMELINE));
    }
}
