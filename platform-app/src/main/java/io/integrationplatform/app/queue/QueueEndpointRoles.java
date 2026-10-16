package io.integrationplatform.app.queue;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * F10-narrow (row278 release-base plan step 8): the queue summary's rule for the tenancy module's interceptor -
 * server-side, for the bearer and the console session chain alike. The figures are reads of message/delivery
 * state, so they ride the existing {@code READ_MESSAGE_TIMELINE} operation (tenant admin, support and read-only;
 * never the agent) instead of inventing a new operation. A queue endpoint without a line here fails
 * platform-app's {@code ApiOperationRulesCompletenessTest} (the same guard ConfigEndpointRoles rides).
 */
@Component
public class QueueEndpointRoles implements ApiOperationRules {

    static final String QUEUE = "/api/v1/admin/queue";

    @Override
    public List<Rule> rules() {
        return List.of(
                // F10-narrow: the dashboard's only operation - a live read of the state registers
                new Rule("GET", QUEUE + "/summary", Operation.READ_MESSAGE_TIMELINE));
    }
}