package io.integrationplatform.ingest.internal.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.integrationplatform.ingest.internal.outbox.AppRoleHarness;
import io.integrationplatform.ingest.internal.outbox.IngestTestImages;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-010: the {@code V2_1_1__idempotency_key.sql} storage, proven as the application role with the same error-deny
 * RLS idiom as the outbox (ADR-009 Decision 5, ADR-007): a claim of one tenant is invisible and untouchable to
 * another, the setting must be bound or every statement fails closed, and the role can only add claims - never
 * rewrite or erase them (history of what was consumed is what makes duplicate detection trustworthy).
 * Container group (R03, R06); no Spring context (clean-room, same reason as the other container suites).
 *
 * <pre>
 * &#64;negative &#64;tenant
 * Scenario: One tenant's claims are invisible to another
 *   Given claim rows of tenant A and tenant B
 *   When the app role reads with tenant A stamped
 *   Then only A's claim is visible, rewriting or erasing B's changes nothing
 *
 * &#64;negative
 * Scenario: The app role cannot rewrite or delete a claim
 *   When it issues UPDATE or DELETE on the claims table
 *   Then PostgreSQL refuses it (grants are SELECT + INSERT only)
 *
 * &#64;negative
 * Scenario: A foreign claim cannot even be inserted, and no setting fails closed
 *   When the app role inserts with tenant B's id while tenant A is stamped, or reads without any setting
 *   Then WITH CHECK refuses the insert and the read fails on the missing app.tenant_id
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class IdempotencyKeyRlsContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IngestTestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

    private long claimA;
    private long claimB;

    @BeforeAll
    void seedAsOwner() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        claimA = seedClaim(TENANT_A, "dedup-rls/tenant-a-key");
        claimB = seedClaim(TENANT_B, "dedup-rls/tenant-b-key");
    }

    private long seedClaim(UUID tenant, String key) throws SQLException {
        try (Connection owner = AppRoleHarness.ownerConnection(POSTGRES)) {
            try (PreparedStatement tenantRow = owner.prepareStatement(
                    "insert into public.tenant (id, code, status) values (?::uuid, ?, 'ACTIVE')")) {
                tenantRow.setObject(1, tenant);
                tenantRow.setString(2, key.equals("dedup-rls/tenant-a-key") ? "dedup-rls-a" : "dedup-rls-b");
                tenantRow.executeUpdate();
            }
            try (PreparedStatement claim = owner.prepareStatement(
                    "insert into public.idempotency_key (tenant_id, idempotency_key) values (?::uuid, ?) returning id")) {
                claim.setObject(1, tenant);
                claim.setString(2, "outbox:" + key);
                try (ResultSet rs = claim.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private Connection appRole(String tenantId) throws SQLException {
        AppRoleHarness.Login login = AppRoleHarness.grantAppRoleLogin(POSTGRES);
        Connection connection = AppRoleHarness.connectAsAppRole(POSTGRES, login);
        if (tenantId != null) {
            AppRoleHarness.bindTenantSetting(connection, tenantId);
        }
        return connection;
    }

    @Test
    void theAppRoleSeesOnlyItsOwnTenantsClaims() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString());
                PreparedStatement read = connection.prepareStatement(
                        "select id from public.idempotency_key");
                ResultSet rs = read.executeQuery()) {
            assertThat(rs.next()).as("tenant A sees its claim").isTrue();
            assertThat(rs.getLong(1)).isEqualTo(claimA);
            assertThat(rs.next()).as("tenant A sees exactly one claim").isFalse();
        }
    }

    @Test
    void theAppRoleCannotDeleteARewrittenClaim() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString())) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement remove = connection.prepareStatement(
                                "delete from public.idempotency_key where id = ?")) {
                            remove.setLong(1, claimA);
                            remove.executeUpdate();
                        }
                    })
                    .withMessageContaining("permission denied");
        }
    }

    @Test
    void theAppRoleCannotRewriteAClaimKey() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString())) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement rewrite = connection.prepareStatement(
                                "update public.idempotency_key set idempotency_key = 'taken over' where id = ?")) {
                            rewrite.setLong(1, claimA);
                            rewrite.executeUpdate();
                        }
                    })
                    .withMessageContaining("permission denied");
        }
    }

    @Test
    void aClaimForAnotherTenantIsRefusedByTheCheckPolicy() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString())) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement foreign = connection.prepareStatement(
                                "insert into public.idempotency_key (tenant_id, idempotency_key) values (?::uuid, ?)")) {
                            foreign.setObject(1, TENANT_B);
                            foreign.setString(2, "outbox:stolen");
                            foreign.executeUpdate();
                        }
                    })
                    .withMessageContaining("row-level security");
        }
    }

    @Test
    void withoutTheTenantSettingEveryStatementFailsClosed() throws SQLException {
        try (Connection connection = appRole(null)) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement read = connection.prepareStatement(
                                "select id from public.idempotency_key");
                                ResultSet rs = read.executeQuery()) {
                            rs.next();
                        }
                    })
                    .withMessageContaining("app.tenant_id");
        }
    }
}
