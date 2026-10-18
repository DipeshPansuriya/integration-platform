package io.integrationplatform.app.message;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E13 N2 (T111): the operator retry and replay rules for the tenancy interceptor. Both re-send a failed delivery, so
 * both bind {@code REPLAY_DEAD_LETTER} (tenant admin and support; never read-only or the agent). Kept beside
 * {@link MessageEndpointRoles} rather than in it, so the T110 reads and the T111 writes stay separately owned.
 */
@Component
public class DeliveryOperatorEndpointRoles implements ApiOperationRules {

    static final String DELIVERIES = "/api/v1/admin/messages/deliveries";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("POST", DELIVERIES + "/{deliveryId}/replay", Operation.REPLAY_DEAD_LETTER),
                new Rule("POST", DELIVERIES + "/{deliveryId}/retry", Operation.REPLAY_DEAD_LETTER));
    }
}
