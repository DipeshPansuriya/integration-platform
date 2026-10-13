package io.integrationplatform.config.rule.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E7 (T-163): the lookup endpoints and the {@code RolePolicy} operation the tenancy interceptor enforces for each.
 * Changing a lookup entry is configuration editing; listing its entries is a read. The pattern follows
 * {@link RuleEndpointRoles}; {@link io.integrationplatform.config.adapter.in.ConfigEndpointRoles} is another
 * lane's file and is never touched.
 */
@Component
public class LookupEndpointRoles implements ApiOperationRules {

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", LookupAdminController.LOOKUP_ENTRIES, Operation.READ_CONFIGURATION),
                new Rule("PUT", LookupAdminController.LOOKUP_ENTRY, Operation.EDIT_CONFIGURATION));
    }
}