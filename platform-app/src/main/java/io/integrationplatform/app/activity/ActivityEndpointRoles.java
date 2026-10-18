package io.integrationplatform.app.activity;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Row327 T118 (E12-F2-S2, T-069): the activity view's rules for the tenancy module's interceptor - server-side, for
 * the bearer and the console session chain alike. The activity view is an operational read of the tenant's own
 * audit events, so it rides the existing {@code READ_MESSAGE_TIMELINE} operation (tenant admin, support and
 * read-only; never the agent), the way {@code QueueEndpointRoles} does, instead of inventing a new operation. An
 * endpoint without a line here fails platform-app's {@code ApiOperationRulesCompletenessTest}.
 */
@Component
public class ActivityEndpointRoles implements ApiOperationRules {

    static final String ACTIVITY = "/api/v1/activity";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", ACTIVITY, Operation.READ_MESSAGE_TIMELINE),
                new Rule("GET", ACTIVITY + "/{eventId}", Operation.READ_MESSAGE_TIMELINE));
    }
}
