package io.integrationplatform.config.destination.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.DbBackedAllowList;
import io.integrationplatform.config.destination.DbBackedAllowList.HostEntry;
import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.RolePolicy;
import io.integrationplatform.tenancy.RolePolicy.Caller;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Row 578 (CWE-862 / CWE-918): the single GLOBAL SSRF allow-list is operator-owned, so every route of
 * {@link OperatorAllowedHostController} is an OPERATOR_AGENT operation - the role interceptor refuses every tenant
 * role (and a source credential) with 403 before the handler runs.
 */
class OperatorAllowedHostEndpointRolesTest {

    static final String HOSTS = "/api/v1/admin/operator/allowed-hosts";

    @Test
    @DisplayName("every allow-list route maps to an operator-agent operation")
    void everyRouteMapsToItsOperatorOperation() {
        assertThat(new OperatorAllowedHostEndpointRoles().rules()).containsExactlyInAnyOrder(
                new Rule("GET", HOSTS, Operation.READ_ALLOWED_HOSTS),
                new Rule("POST", HOSTS, Operation.EDIT_ALLOWED_HOSTS),
                new Rule("DELETE", HOSTS + "/{id}", Operation.EDIT_ALLOWED_HOSTS));
        assertThat(OperatorAllowedHostController.PATH).isEqualTo(HOSTS);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Refused: no tenant role (admin, configurator, approver, support, read-only) holds any route")
    void noTenantRoleMayCallAnyRoute() {
        for (Rule rule : new OperatorAllowedHostEndpointRoles().rules()) {
            assertThat(rule.operation().caller()).as(rule.key()).isEqualTo(Caller.OPERATOR_AGENT);
            for (Role role : Role.values()) {
                assertThat(RolePolicy.allows(rule.operation(), EnumSet.of(role)))
                        .as("%s must refuse %s", rule.key(), role).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the handlers need no bound tenant user: the operator agent (no tenant) reaches list, add and remove")
    void handlersWorkWithoutABoundTenantUser() {
        DbBackedAllowList list = mock(DbBackedAllowList.class);
        UUID id = UUID.randomUUID();
        when(list.list()).thenReturn(List.of(new HostEntry(id, "*.example.com")));
        when(list.add("api.example.com")).thenReturn(new HostEntry(id, "api.example.com"));
        when(list.remove(id)).thenReturn(true);
        OperatorAllowedHostController controller = new OperatorAllowedHostController(list);

        assertThat(controller.list().getBody()).containsExactly(Map.of("id", id.toString(), "pattern", "*.example.com"));
        assertThat(controller.add(Map.of("pattern", "api.example.com")).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.remove(id).getStatusCode().value()).isEqualTo(204);
        verify(list).remove(id);
    }
}
