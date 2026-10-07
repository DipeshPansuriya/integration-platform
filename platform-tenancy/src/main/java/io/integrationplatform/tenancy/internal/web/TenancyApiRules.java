package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;

/** The tenancy module's own {@code /api} endpoints and their operations (T-099): {@code GET /api/v1/me} - any bound human. */
final class TenancyApiRules implements ApiOperationRules {

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("GET", "/api/v1/me", Operation.READ_OWN_IDENTITY));
    }
}
