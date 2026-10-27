package io.integrationplatform.config.system.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.RolePolicy;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Row 484 (security finding H-1, PR 34): the destination allow-list is ONE global, non-tenant-scoped list owned by the
 * operator ({@code platform.config.destinations.allowed-hosts}, ADR-010, T97). Until the operator login exists
 * (ADR-009) no {@code /api} route may edit it, so no tenant role (TENANT_ADMIN and CONFIGURATOR hold
 * EDIT_CONFIGURATION) can. The rules of the systems endpoints are pinned exactly: a new line here is a reviewed one.
 */
class SystemEndpointRolesTest {

    private static final String SYSTEMS = "/api/v1/admin/systems";

    private static final Pattern NAMES_THE_GLOBAL_LIST = Pattern.compile("(?i)allow(ed)?[-_ ]?(list|hosts?)");

    @Test
    @DisplayName("the systems rules are exactly the reviewed systems routes - nothing else rides in this class")
    void rulesAreExactlyTheSystemsRoutes() {
        assertThat(new SystemEndpointRoles().rules()).containsExactlyInAnyOrder(
                new Rule("GET", SYSTEMS, Operation.READ_CONFIGURATION),
                new Rule("GET", SYSTEMS + "/{systemId}", Operation.READ_CONFIGURATION),
                new Rule("PUT", SYSTEMS + "/{systemId}", Operation.EDIT_CONFIGURATION),
                new Rule("GET", SYSTEMS + "/{systemId}/endpoint-auth", Operation.READ_CONFIGURATION),
                new Rule("PUT", SYSTEMS + "/{systemId}/endpoint-auth", Operation.MANAGE_TARGET_CREDENTIAL),
                new Rule("POST", SYSTEMS + "/{systemId}/test-connection", Operation.EDIT_CONFIGURATION),
                new Rule("GET", SYSTEMS + "/{systemId}/approved-tables", Operation.READ_CONFIGURATION),
                new Rule("POST", SYSTEMS + "/{systemId}/approved-tables", Operation.EDIT_CONFIGURATION),
                new Rule("POST", SYSTEMS + "/{systemId}/approved-tables/{id}/approve", Operation.APPROVE_DB_TABLE),
                new Rule("POST", SYSTEMS + "/{systemId}/approved-tables/{id}/revoke", Operation.APPROVE_DB_TABLE));
    }

    @Test
    @DisplayName("no tenant role - TENANT_ADMIN, CONFIGURATOR or any other - is granted a route that names the global"
            + " allow-list")
    void noRoleMayReachTheGlobalAllowList() {
        List<String> granted = new ArrayList<>();
        for (Rule rule : new SystemEndpointRoles().rules()) {
            if (!NAMES_THE_GLOBAL_LIST.matcher(rule.pattern()).find()) {
                continue;
            }
            for (Role role : Role.values()) {
                if (RolePolicy.allows(rule.operation(), EnumSet.of(role))) {
                    granted.add(rule.key() + " <- " + role);
                }
            }
        }
        assertThat(granted).isEmpty();
    }
}
