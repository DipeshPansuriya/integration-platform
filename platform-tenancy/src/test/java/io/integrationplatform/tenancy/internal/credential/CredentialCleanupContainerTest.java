package io.integrationplatform.tenancy.internal.credential;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USER_ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenancyTestApplication;
import io.integrationplatform.tenancy.api.CredentialCleanup;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Row 327 T158 (E14 T-203 + T-144; ADR-007 Decision 3, ADR-012 :165) against PostgreSQL with RLS, as the restricted
 * login: the clean-up of the BOUND tenant revokes only that tenant's stale PENDING credentials and expired rotations
 * (each REVOKED and flagged {@code idp_disable_pending} for the reconciliation) - the other tenant's identical rows are
 * untouched - and with no tenant bound it refuses instead of sweeping every tenant. No identity-provider admin client
 * is configured here (no {@code PLATFORM_ADMIN_CLIENT_SECRET}), so the flags stay for the reconciliation; the disable
 * path is IdpDisableControlsTest. Container group (R03); one Docker suite at a time (R06).
 */
@Tag("container")
@SpringBootTest(classes = TenancyTestApplication.class, properties = {
    "spring.config.import=optional:classpath:config/tenancy-security.yml",
    "spring.flyway.enabled=false"
})
@Import(CredentialCleanupContainerTest.RestrictedLogin.class)
class CredentialCleanupContainerTest {

    private static final UUID SYSTEM_A = UUID.randomUUID();
    private static final UUID SYSTEM_B = UUID.randomUUID();

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        // never called here: the resource server only needs an issuer to start; no admin client is configured
        registry.add("OIDC_ISSUER_URI", () -> RlsTestDatabase.ISSUER);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestrictedLogin {

        /** The application's restricted login (ipaas_app), as in production; Flyway already ran as the owner. */
        @Bean
        HikariDataSource dataSource() {
            return RlsTestDatabase.get().appPool(4);
        }
    }

    @BeforeAll
    static void sourceSystems() throws SQLException {
        system(SYSTEM_A, TENANT_A);
        system(SYSTEM_B, TENANT_B);
    }

    @AfterEach
    void unbind() {
        SecurityContextHolder.clearContext();
    }

    @Autowired
    private CredentialCleanup cleanup;

    @Test
    void theBoundTenantsStaleAndExpiredCredentialsAreRevokedAndFlaggedAndNoOtherTenantsAre() throws SQLException {
        UUID stalePendingA = credential(TENANT_A, SYSTEM_A, "PENDING", "now() - interval '1 hour'", null);
        UUID freshPendingA = credential(TENANT_A, SYSTEM_A, "PENDING", "now()", null);
        UUID expiredRotationA = credential(TENANT_A, SYSTEM_A, "ACTIVE", "now() - interval '8 days'",
                "now() - interval '1 minute'");
        UUID stalePendingB = credential(TENANT_B, SYSTEM_B, "PENDING", "now() - interval '1 hour'", null);
        UUID expiredRotationB = credential(TENANT_B, SYSTEM_B, "ACTIVE", "now() - interval '8 days'",
                "now() - interval '1 minute'");

        bind(TENANT_A);
        CredentialCleanup.Result result = cleanup.cleanUpBoundTenant();

        assertThat(result.abandoned()).isEqualTo(1);
        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.cleared()).as("no identity-provider admin client on this instance").isZero();
        assertThat(row(stalePendingA)).isEqualTo("REVOKED|true");
        assertThat(row(expiredRotationA)).isEqualTo("REVOKED|true");
        assertThat(row(freshPendingA)).as("inside the 15 minute pending timeout").isEqualTo("PENDING|false");
        assertThat(row(stalePendingB)).as("tenant B's row is never swept by tenant A's run").isEqualTo("PENDING|false");
        assertThat(row(expiredRotationB)).isEqualTo("ACTIVE|false");
    }

    @Test
    void withNoTenantBoundTheCleanUpRefusesAndTouchesNothing() throws SQLException {
        UUID stalePendingB = credential(TENANT_B, SYSTEM_B, "PENDING", "now() - interval '1 hour'", null);

        assertThatThrownBy(() -> cleanup.cleanUpBoundTenant()).isInstanceOf(IllegalStateException.class);

        assertThat(row(stalePendingB)).isEqualTo("PENDING|false");
    }

    private static void system(UUID id, UUID tenant) throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement insert =
                owner.prepareStatement("insert into public.system (id, tenant_id, code) values (?::uuid, ?::uuid, ?)")) {
            insert.setString(1, id.toString());
            insert.setString(2, tenant.toString());
            insert.setString(3, "erp-t203-" + id);
            insert.executeUpdate();
        }
    }

    /** This test's own SQL fixture, written as the owner: created_at and revoke_after are SQL expressions. */
    private static UUID credential(UUID tenant, UUID system, String status, String createdAt, String revokeAfter)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement insert =
                owner.prepareStatement("insert into public.source_credential (id, tenant_id, source_system_id,"
                        + " customer_id, issuer, client_id, status, created_at, revoke_after) values (?::uuid,"
                        + " ?::uuid, ?::uuid, ?, ?, ?, ?, " + createdAt + ", "
                        + (revokeAfter == null ? "null" : revokeAfter) + ")")) {
            insert.setString(1, id.toString());
            insert.setString(2, tenant.toString());
            insert.setString(3, system.toString());
            insert.setString(4, "CUSTOMER-T203");
            insert.setString(5, RlsTestDatabase.ISSUER);
            insert.setString(6, "src-" + id);
            insert.setString(7, status);
            insert.executeUpdate();
        }
        return id;
    }

    private static String row(UUID credentialId) throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement select =
                owner.prepareStatement("select status || '|' || idp_disable_pending from public.source_credential"
                        + " where id = ?::uuid")) {
            select.setString(1, credentialId.toString());
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    /** A request-bound tenant, as the tenancy module's own contexts bind one (the job binds through the worker). */
    private static void bind(UUID tenant) {
        Jwt jwt = Jwt.withTokenValue("bound").header("alg", "none").subject("alice").issuer("https://idp.test")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        LoginBinding login = new LoginBinding(USER_ALICE, "ACTIVE", tenant, "tenant-a", "ACTIVE",
                ZoneId.of("Asia/Kolkata"));
        SecurityContextHolder.getContext().setAuthentication(
                new TenantBoundAuthentication(new JwtAuthenticationToken(jwt), login));
    }
}
