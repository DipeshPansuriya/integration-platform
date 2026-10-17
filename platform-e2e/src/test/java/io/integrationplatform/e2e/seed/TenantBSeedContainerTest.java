package io.integrationplatform.e2e.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.integrationplatform.e2e.harness.E2eContainers;
import io.integrationplatform.e2e.seed.TenantBSeed.SeededTenant;
import io.integrationplatform.e2e.seed.TenantSeedPlan.SeedUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Row 327 T157 (acceptance 3): the tenant B seed against the REAL directory schema (platform-tenancy's migrations, read
 * from disk - never copied) and a REAL Keycloak with the compose realm: tenant A and tenant B are seeded; B's
 * {@code TENANT_ADMIN} and {@code READ_ONLY} users log in, and the platform's own login bootstrap
 * ({@code public.ipaas_login_bootstrap(iss, sub)}) resolves each of them to tenant B - never A - and A's admin to A.
 * Tagged {@code container}: runs only under {@code ./mvnw -Pcontainer test}, one Docker suite at a time (R06).
 */
@Tag("container")
class TenantBSeedContainerTest {

    /** The same tags as compose/compose.yaml's postgres and keycloak services. */
    static final String POSTGRES_IMAGE = "postgres:17.11-alpine";
    static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.0";

    /** Directory schema only: the restricted role, tenant + app_user, the login bootstrap, role_grant. */
    static final List<String> DIRECTORY_MIGRATIONS = List.of(
            "V1_3_1__restricted_application_role.sql",
            "V1_3_2__tenant_directory_and_app_user_rls.sql",
            "V1_3_3__ipaas_login_bootstrap.sql",
            "V2_2_6__role_grant.sql");

    private static final int POSTGRES_PORT = 5432;
    private static final int KEYCLOAK_HTTP = 8080;
    private static final int KEYCLOAK_MANAGEMENT = 9000;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void tenantBsAdminAndReadOnlyUserResolveToTenantBNotTenantA() throws Exception {
        String dbUser = "e2e_owner_" + TenantSeedPlan.hex(RANDOM, 8);
        String dbPassword = token(24);
        String kcAdmin = "e2e-admin-" + TenantSeedPlan.hex(RANDOM, 8);
        String kcPassword = token(24);
        try (Network network = Network.newNetwork();
                GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                        .withNetwork(network)
                        .withEnv("POSTGRES_USER", dbUser)
                        .withEnv("POSTGRES_PASSWORD", dbPassword)
                        .withEnv("POSTGRES_DB", "directory")
                        .withEnv("TZ", "Etc/UTC")
                        .withExposedPorts(POSTGRES_PORT)
                        .withCreateContainerCmdModifier(E2eContainers.loopbackOnly(POSTGRES_PORT))
                        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                                .withStartupTimeout(Duration.ofMinutes(2)));
                GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
                        .withNetwork(network)
                        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", kcAdmin)
                        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", kcPassword)
                        .withEnv("KC_HEALTH_ENABLED", "true")
                        .withEnv("JAVA_OPTS_KC_HEAP", "-Xms64m -Xmx512m")
                        .withEnv("TZ", "Etc/UTC")
                        .withEnv("OIDC_AUDIENCE", "e2e-api-" + TenantSeedPlan.hex(RANDOM, 8))
                        .withEnv("CONSOLE_OIDC_CLIENT_SECRET", token(32))
                        .withEnv("CONSOLE_OIDC_REDIRECT_URI", "http://127.0.0.1/login/oauth2/code/keycloak")
                        .withEnv("CONSOLE_POST_LOGOUT_REDIRECT_URI", "http://127.0.0.1/")
                        .withEnv("PLATFORM_ADMIN_CLIENT_SECRET", token(32))
                        .withCopyFileToContainer(MountableFile.forHostPath(repo().resolve(
                                "compose/keycloak/platform-realm.json")), "/opt/keycloak/data/import/platform-realm.json")
                        .withCommand("start-dev", "--import-realm")
                        .withExposedPorts(KEYCLOAK_HTTP, KEYCLOAK_MANAGEMENT)
                        .withCreateContainerCmdModifier(E2eContainers.loopbackOnly(KEYCLOAK_HTTP, KEYCLOAK_MANAGEMENT))
                        .waitingFor(Wait.forHttp("/health/ready").forPort(KEYCLOAK_MANAGEMENT)
                                .withStartupTimeout(Duration.ofMinutes(4)))) {
            postgres.start();
            keycloak.start();
            KeycloakRealmUsers realm = new KeycloakRealmUsers("http://" + keycloak.getHost() + ":"
                    + keycloak.getMappedPort(KEYCLOAK_HTTP), kcAdmin, kcPassword);

            try (Connection owner = DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(POSTGRES_PORT) + "/directory", dbUser, dbPassword)) {
                migrate(owner);

                String tenantACode = TenantSeedPlan.MARKER + "-a-" + TenantSeedPlan.hex(RANDOM, 8);
                SeedUser tenantAAdmin = SeedUser.of("a" + TenantSeedPlan.hex(RANDOM, 7), TenantSeedPlan.TENANT_ADMIN,
                        RANDOM);
                String issuer = realm.issuer();
                String tenantASubject = realm.create(tenantAAdmin);
                UUID tenantA = DirectorySeed.apply(owner, tenantACode, issuer,
                        Map.of(tenantASubject, TenantSeedPlan.TENANT_ADMIN));

                TenantSeedPlan plan = TenantSeedPlan.tenantB(tenantACode);
                SeededTenant tenantB = TenantBSeed.apply(plan, owner, realm);
                assertThat(tenantB.tenantId()).as("tenant B is a second tenant").isNotEqualTo(tenantA);

                for (String role : List.of(TenantSeedPlan.TENANT_ADMIN, TenantSeedPlan.READ_ONLY)) {
                    String[] issSub = issuerAndSubject(realm.accessToken(plan.user(role)));
                    assertThat(issSub[1]).as("%s's token subject", role).isEqualTo(tenantB.subjectByRole().get(role));
                    Login login = bootstrap(owner, issSub[0], issSub[1]);
                    assertThat(login.tenantId()).as("%s resolves to tenant B", role).isEqualTo(tenantB.tenantId())
                            .isNotEqualTo(tenantA);
                    assertThat(login.tenantCode()).isEqualTo(plan.tenantCode()).isNotEqualTo(tenantACode);
                    assertThat(rolesOf(owner, login.userId())).as("%s's grants", role).containsExactly(role);
                }

                String[] aIssSub = issuerAndSubject(realm.accessToken(tenantAAdmin));
                assertThat(bootstrap(owner, aIssSub[0], aIssSub[1]).tenantId()).as("A's admin stays in A")
                        .isEqualTo(tenantA);
            }
        }
    }

    private record Login(UUID userId, UUID tenantId, String tenantCode) {
    }

    private static Login bootstrap(Connection owner, String issuer, String subject) throws Exception {
        try (PreparedStatement call = owner.prepareStatement(
                "SELECT user_id, tenant_id, tenant_code FROM public.ipaas_login_bootstrap(?, ?)")) {
            call.setString(1, issuer);
            call.setString(2, subject);
            try (ResultSet row = call.executeQuery()) {
                assertThat(row.next()).as("the login bootstrap knows (%s, %s)", issuer, subject).isTrue();
                return new Login(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3));
            }
        }
    }

    private static List<String> rolesOf(Connection owner, UUID userId) throws Exception {
        try (PreparedStatement query = owner.prepareStatement(
                "SELECT role FROM public.role_grant WHERE user_id = ? ORDER BY role")) {
            query.setObject(1, userId);
            List<String> roles = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    roles.add(rows.getString(1));
                }
            }
            return roles;
        }
    }

    /** The token's {@code iss} and {@code sub} (the payload only; the signature is Keycloak's own). */
    private static String[] issuerAndSubject(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        return new String[] {JsonPath.read(payload, "$.iss"), JsonPath.read(payload, "$.sub")};
    }

    private static void migrate(Connection owner) throws Exception {
        Path migrations = repo().resolve("platform-tenancy/src/main/resources/db/migration");
        try (Statement statement = owner.createStatement()) {
            for (String file : DIRECTORY_MIGRATIONS) {
                statement.execute(Files.readString(migrations.resolve(file), StandardCharsets.UTF_8));
            }
        }
    }

    private static Path repo() {
        return Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize().getParent();
    }

    private static String token(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
