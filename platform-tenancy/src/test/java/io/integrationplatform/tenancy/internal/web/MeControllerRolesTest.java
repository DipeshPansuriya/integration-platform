package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.RoleGrants;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/**
 * Row 540 B: GET /api/v1/me also names the caller's roles (from role_grant, never from the token), so the console can
 * hide a role-gated form BEFORE the first Save. Additive: the four earlier fields are unchanged.
 */
class MeControllerRolesTest {

    private static final UUID USER = UUID.randomUUID(), TENANT = UUID.randomUUID();

    private final RequestTenantContext tenantContext = mock(RequestTenantContext.class);
    private final RoleGrants grants = mock(RoleGrants.class);

    private void bound() {
        when(tenantContext.currentBinding()).thenReturn(Optional.of(
                new LoginBinding(USER, "ACTIVE", TENANT, "acme", "ACTIVE", ZoneId.of("Europe/Berlin"))));
    }

    @Test
    void theAnswerNamesTheCallersRolesSortedAndKeepsTheEarlierFields() {
        bound();
        when(grants.rolesOf(USER)).thenReturn(EnumSet.of(Role.TENANT_ADMIN, Role.CONFIGURATOR));

        MeController.Me me = new MeController(tenantContext, grants, TransactionOperations.withoutTransaction()).me();

        assertThat(me.roles()).containsExactly("CONFIGURATOR", "TENANT_ADMIN");
        assertThat(me.userId()).isEqualTo(USER);
        assertThat(me.tenantId()).isEqualTo(TENANT);
        assertThat(me.tenantCode()).isEqualTo("acme");
        assertThat(me.timeZone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void aUserWithNoGrantGetsAnEmptyList() {
        bound();
        when(grants.rolesOf(USER)).thenReturn(EnumSet.noneOf(Role.class));

        assertThat(new MeController(tenantContext, grants, TransactionOperations.withoutTransaction()).me().roles())
                .isEmpty();
    }

    @Test
    void withoutARoleDirectoryTheRolesAreEmptyAndNothingIsRead() {
        bound();

        assertThat(new MeController(tenantContext, null, TransactionOperations.withoutTransaction()).me().roles())
                .isEmpty();
        verifyNoInteractions(grants);
    }

    @Test
    void aSourceSystemCallStillGets403AndReadsNoRoles() {
        when(tenantContext.currentBinding()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new MeController(tenantContext, grants, TransactionOperations.withoutTransaction()).me())
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(grants);
    }
}
