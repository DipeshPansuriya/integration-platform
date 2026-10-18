package io.integrationplatform.e2e.seed;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Row 327 T157 (Dipesh option 1, memory m-68d957): what the TEST-ONLY tenant B seed creates for SC-07 and SEC-01 - a
 * second tenant next to tenant A with exactly one {@code TENANT_ADMIN} user, one {@code READ_ONLY} user and one
 * client-credentials source credential. Every code, login and password is generated per run and held in memory only
 * (R07: no literal secret in any file). The roles are names of {@code public.role_grant}'s closed CHECK set
 * (platform-tenancy {@code Role}; named, not imported - this module is a black box).
 *
 * <p>Test-scoped by construction: the package and {@link #MARKER} are what arch-test's ModuleScopeGuard searches every
 * production source, migration and the compose realm import for - never a Flyway migration, application*.yml, realm
 * import or default profile.
 */
public record TenantSeedPlan(String tenantCode, List<SeedUser> users, SourceCredentialSpec credential) {

    /** Prefix of every generated tenant code and login; the scope guard's production marker. */
    public static final String MARKER = "e2e-seed";

    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String READ_ONLY = "READ_ONLY";

    private static final List<String> TENANT_B_ROLES = List.of(TENANT_ADMIN, READ_ONLY);
    private static final int RUN_TOKEN = 8;
    private static final int PASSWORD_LENGTH = 40;

    public TenantSeedPlan {
        Objects.requireNonNull(tenantCode, "tenantCode");
        users = List.copyOf(users);
        Objects.requireNonNull(credential, "credential");
    }

    /** Tenant B for this run, with a code that is never {@code tenantACode}. */
    public static TenantSeedPlan tenantB(String tenantACode) {
        return tenantB(tenantACode, new SecureRandom());
    }

    /** Tenant B drawn from {@code random} (a seeded generator only in tests of the plan itself). */
    public static TenantSeedPlan tenantB(String tenantACode, RandomGenerator random) {
        if (tenantACode == null || tenantACode.isBlank()) {
            throw new IllegalArgumentException("tenant A's code is required: tenant B's code must differ from it");
        }
        String run;
        String code;
        do {
            run = hex(random, RUN_TOKEN);
            code = MARKER + "-b-" + run;
        } while (code.equals(tenantACode.strip()));
        String runToken = run;
        List<SeedUser> users = TENANT_B_ROLES.stream().map(role -> SeedUser.of(runToken, role, random)).toList();
        SourceCredentialSpec credential = new SourceCredentialSpec("B-SRC-" + run.toUpperCase(Locale.ROOT),
                "tenant B source " + run, "customer-b-" + run, "client_credentials");
        return new TenantSeedPlan(code, users, credential);
    }

    /** The roles the plan grants, in seeding order. */
    public Set<String> roles() {
        Set<String> roles = new LinkedHashSet<>();
        users.forEach(user -> roles.add(user.role()));
        return roles;
    }

    /** The one user holding {@code role}. */
    public SeedUser user(String role) {
        return users.stream().filter(user -> user.role().equals(role)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the plan has no " + role + " user"));
    }

    static String hex(RandomGenerator random, int length) {
        StringBuilder text = new StringBuilder(length);
        while (text.length() < length) {
            text.append(Character.forDigit(random.nextInt(16), 16));
        }
        return text.toString();
    }

    /** A realm user and its one role; the password lives in memory only and is never printed. */
    public record SeedUser(String username, String password, String role, String email, String firstName,
            String lastName) {

        static SeedUser of(String run, String role, RandomGenerator random) {
            String username = MARKER + "-" + run + "-" + role.toLowerCase(Locale.ROOT).replace('_', '-');
            // .invalid (RFC 2606) never reaches a mailbox; names and e-mail keep Keycloak's user profile complete.
            return new SeedUser(username, hex(random, PASSWORD_LENGTH), role, username + "@e2e.invalid", "E2E",
                    role.toLowerCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return "SeedUser[username=" + username + ", role=" + role + ", password=********]";
        }
    }

    /** The source system tenant B registers and the client-credentials credential it issues for it. */
    public record SourceCredentialSpec(String systemCode, String systemName, String customerId, String grantType) {
    }
}
