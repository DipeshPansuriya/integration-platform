package io.integrationplatform.e2e.seed;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/** RED stub (row 327 T157). */
public record TenantSeedPlan(String tenantCode, List<SeedUser> users, SourceCredentialSpec credential) {

    public static final String MARKER = "";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String READ_ONLY = "READ_ONLY";

    public static TenantSeedPlan tenantB(String tenantACode) {
        throw new UnsupportedOperationException("RED");
    }

    public static TenantSeedPlan tenantB(String tenantACode, RandomGenerator random) {
        throw new UnsupportedOperationException("RED");
    }

    public Set<String> roles() {
        throw new UnsupportedOperationException("RED");
    }

    public SeedUser user(String role) {
        throw new UnsupportedOperationException("RED");
    }

    public record SeedUser(String username, String password, String role, String email, String firstName,
            String lastName) {
    }

    public record SourceCredentialSpec(String systemCode, String systemName, String customerId, String grantType) {
    }
}
