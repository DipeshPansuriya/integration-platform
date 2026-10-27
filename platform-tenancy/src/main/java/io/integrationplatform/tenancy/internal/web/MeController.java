package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.RoleGrants;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/v1/me - the bound user and tenant from the login binding, plus the caller's roles (row 540: so the console
 * can hide a role-gated form before the first Save; the server's role check stays the only gate). The roles are read
 * from {@code role_grant} in a read-only, tenant-bound transaction, the same way the role check reads them - never from
 * the token. Without a role directory (a slice test with no database) the list is empty: the console then offers the
 * form and the server's 403 still decides.
 */
@RestController
class MeController {

    /** @param roles the caller's role names (the {@link Role} enum), sorted; empty when none is granted */
    record Me(UUID userId, UUID tenantId, String tenantCode, String timeZone, List<String> roles) {
    }

    private final RequestTenantContext tenantContext;
    private final RoleGrants roleGrants;
    private final TransactionOperations transactions;

    @Autowired
    MeController(RequestTenantContext tenantContext, ObjectProvider<RoleGrants> roleGrants,
            ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(tenantContext, transactionManagers.getIfAvailable() == null ? null : roleGrants.getIfAvailable(),
                readOnly(transactionManagers.getIfAvailable()));
    }

    MeController(RequestTenantContext tenantContext, RoleGrants roleGrants, TransactionOperations transactions) {
        this.tenantContext = tenantContext;
        this.roleGrants = roleGrants;
        this.transactions = transactions;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager manager) {
        if (manager == null) {
            return TransactionOperations.withoutTransaction();
        }
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        return template;
    }

    @GetMapping("/api/v1/me")
    Me me() {
        // A source system's call binds a tenant but no user (E4 T-098): it is not a console user - 403.
        LoginBinding binding = tenantContext.currentBinding()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        return new Me(binding.userId(), binding.tenantId(), binding.tenantCode(), binding.timeZone().getId(),
                rolesOf(binding.userId()));
    }

    private List<String> rolesOf(UUID userId) {
        if (roleGrants == null) {
            return List.of();
        }
        Set<Role> roles = transactions.execute(status -> roleGrants.rolesOf(userId));
        return roles == null ? List.of() : roles.stream().map(Role::name).sorted(Comparator.naturalOrder()).toList();
    }
}
