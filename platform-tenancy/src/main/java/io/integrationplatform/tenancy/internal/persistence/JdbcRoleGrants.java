package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import io.integrationplatform.tenancy.port.RoleGrants;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The bound human's roles (T-099) through the tenant-scoped repository base: the statement carries the bound tenant's
 * predicate (R08), runs in the caller's stamped transaction (R11, row-level security as the second wall) and is
 * parameterised with the explicit uuid cast (R09). A role name the code does not know is never granted silently - it
 * fails the read (the table's CHECK and the enum are one closed set).
 */
class JdbcRoleGrants extends TenantScopedRepository implements RoleGrants {

    private static final String SELECT_ROLES = "SELECT role FROM public.role_grant";
    private static final String OF_USER = "user_id = :user_id::uuid";

    JdbcRoleGrants(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public Set<Role> rolesOf(UUID userId) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        query(SELECT_ROLES, OF_USER, Map.of("user_id", userId.toString()), (rs, n) -> Role.valueOf(rs.getString(1)))
                .forEach(roles::add);
        return roles;
    }
}
