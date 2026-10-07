package io.integrationplatform.tenancy.port;

import io.integrationplatform.tenancy.Role;
import java.util.Set;
import java.util.UUID;

/**
 * The roles a bound human holds in the bound tenant (T-099, ADR-007 Decision 5): rows of {@code public.role_grant},
 * never identity-provider roles. Runs in the caller's open, tenant-bound transaction (R08, R11) and only ever sees the
 * bound tenant's rows; a user with no grant holds no role (empty set).
 */
public interface RoleGrants {

    Set<Role> rolesOf(UUID userId);
}
