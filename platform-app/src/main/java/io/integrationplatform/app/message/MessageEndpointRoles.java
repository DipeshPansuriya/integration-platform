package io.integrationplatform.app.message;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E13 N1 (T110, row278 wave): the message tracking reads' rules for the tenancy module's interceptor - server-side,
 * for the bearer and the console session chain alike. Both are reads of message/delivery state, so they bind the
 * {@code READ_MESSAGE_TIMELINE} operation RolePolicy reserved for E13 (tenant admin, support and read-only; never
 * the agent). An endpoint without a line here fails platform-app's {@code ApiOperationRulesCompletenessTest}.
 */
@Component
public class MessageEndpointRoles implements ApiOperationRules {

    static final String MESSAGES = "/api/v1/admin/messages";

    @Override
    public List<Rule> rules() {
        return List.of(
                // T-137 narrow: one message's stages, timestamps and the durations between them
                new Rule("GET", MESSAGES + "/{trackingId}/timeline", Operation.READ_MESSAGE_TIMELINE),
                // E12 failure-diagnosis read side: failed and dead-lettered messages per flow and day
                new Rule("GET", MESSAGES + "/failures", Operation.READ_MESSAGE_TIMELINE),
                // S8: business-key / recent message search (read only, tenant-bound)
                new Rule("GET", MESSAGES, Operation.READ_MESSAGE_TIMELINE),
                // Successfully delivered messages per flow and day
                new Rule("GET", MESSAGES + "/delivered", Operation.READ_MESSAGE_TIMELINE));
    }
}
