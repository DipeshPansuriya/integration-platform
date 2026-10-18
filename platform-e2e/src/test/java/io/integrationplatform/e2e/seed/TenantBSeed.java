package io.integrationplatform.e2e.seed;

import io.integrationplatform.e2e.seed.TenantSeedPlan.SeedUser;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Row 327 T157: applies a {@link TenantSeedPlan} - each realm user through Keycloak's admin API, then the directory
 * rows for its {@code (iss, sub)}. The source credential is issued separately ({@link SourceCredentialSeed}) through
 * the platform's admin API once a signed-in tenant B admin session exists.
 */
public final class TenantBSeed {

    private TenantBSeed() {
    }

    public static SeededTenant apply(TenantSeedPlan plan, Connection owner, KeycloakRealmUsers realm)
            throws SQLException {
        String issuer = realm.issuer();
        Map<String, String> subjectByRole = new LinkedHashMap<>();
        Map<String, String> roleBySubject = new LinkedHashMap<>();
        for (SeedUser user : plan.users()) {
            String subject = realm.create(user);
            subjectByRole.put(user.role(), subject);
            roleBySubject.put(subject, user.role());
        }
        UUID tenantId = DirectorySeed.apply(owner, plan.tenantCode(), issuer, roleBySubject);
        return new SeededTenant(tenantId, plan.tenantCode(), issuer, Map.copyOf(subjectByRole));
    }

    /** What the seed made: the tenant, the issuer its users log in at, and each role's token subject. */
    public record SeededTenant(UUID tenantId, String tenantCode, String issuer, Map<String, String> subjectByRole) {
    }
}
