package io.integrationplatform.ingest.internal.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.integrationplatform.ingest.port.OutboxTopic;
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
 * T-007 / E2-F1 "never lost, never mixed": the frozen {@code V2_0_1__outbox.sql} grants and RLS policy, proven as the
 * application role (the owner connection bypasses row-level security, so this class opens a restricted
 * {@code ipaas_app} login). Container group (R03, R06). No Spring context on purpose (same clean-room reason as
 * {@link OutboxWriterContainerTest}): migration and seeding run on the Flyway-owner autocommit session, every
 * asserted statement runs on its own connection as the app role with the session-local {@code app.tenant_id}
 * setting, so what each proof sees is committed state only.
 *
 * <pre>
 * &#64;negative &#64;tenant
 * Scenario: Outbox rows of another tenant are invisible to the app role
 *   Given outbox rows of tenant A and tenant B
 *   When the app role reads with tenant A stamped
 *   Then only tenant A's row is visible and an update aimed at tenant B's row changes nothing
 *
 * &#64;negative
 * Scenario: The app role cannot delete or rewrite an outbox row
 *   When the app role issues DELETE, or UPDATE of any column but published_at
 *   Then PostgreSQL refuses it; marking published_at is the single allowed write
 *
 * &#64;negative
 * Scenario: No tenant setting fails every statement (error-deny)
 *   Given the app role connection has no app.tenant_id set
 *   When it reads the outbox
 *   Then the statement fails - it does not silently return other tenants' rows
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class OutboxRlsContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IngestTestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-4000-8000-000000000001");

    private long rowA;
    private long rowB;

    @BeforeAll
    void seedAsOwner() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        seedTenant(TENANT_A, "rls-a");
        seedTenant(TENANT_B, "rls-b");
        rowA = seedOutboxRow(TENANT_A, "rls/tenant-a-row");
        rowB = seedOutboxRow(TENANT_B, "rls/tenant-b-row");
    }

    private void seedTenant(UUID id, String code) throws SQLException {
        try (Connection owner = AppRoleHarness.ownerConnection(POSTGRES);
                PreparedStatement insert = owner.prepareStatement(
                        "insert into public.tenant (id, code, status) values (?::uuid, ?, 'ACTIVE')")) {
            insert.setObject(1, id);
            insert.setString(2, code);
            insert.executeUpdate();
        }
    }

    /** Owner-side direct insert (the fixture the relay would have written): RLS and grants do not apply to it. */
    private long seedOutboxRow(UUID tenant, String recordKey) throws SQLException {
        try (Connection owner = AppRoleHarness.ownerConnection(POSTGRES);
                PreparedStatement insert = owner.prepareStatement("""
                        insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket)
                        values (?::uuid, ?, ?::uuid, ?, 7) returning id""")) {
            insert.setObject(1, tenant);
            insert.setString(2, OutboxTopic.MESSAGE_ACCEPTED.topicName());
            insert.setObject(3, MESSAGE_ID);
            insert.setString(4, recordKey);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
    void theAppRoleSeesOnlyItsOwnTenantsRows() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString());
                PreparedStatement read = connection.prepareStatement("select id from public.outbox order by id");
                ResultSet rs = read.executeQuery()) {
            assertThat(rs.next()).as("tenant A sees its row").isTrue();
            assertThat(rs.getLong(1)).isEqualTo(rowA);
            assertThat(rs.next()).as("tenant A sees exactly one row").isFalse();
        }
        try (Connection connection = appRole(TENANT_B.toString());
                PreparedStatement read = connection.prepareStatement("select id from public.outbox");
                ResultSet rs = read.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(rowB);
            assertThat(rs.next()).as("tenant B sees exactly one row").isFalse();
        }
    }

    @Test
    void anUpdateAimedAtAnotherTenantsRowChangesNothing() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString());
                PreparedStatement mark = connection.prepareStatement(
                        "update public.outbox set published_at = now() where id = ?")) {
            mark.setLong(1, rowB);
            assertThat(mark.executeUpdate()).as("row B is invisible under RLS USING").isZero();
        }
    }

    @Test
    void markingPublishedAtIsTheOnlyAllowedUpdate() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString());
                PreparedStatement mark = connection.prepareStatement(
                        "update public.outbox set published_at = now() where id = ?")) {
            mark.setLong(1, rowA);
            assertThat(mark.executeUpdate()).isEqualTo(1);
        }
        try (Connection connection = appRole(TENANT_A.toString());
                PreparedStatement read = connection.prepareStatement(
                        "select published_at is not null from public.outbox where id = ?")) {
            read.setLong(1, rowA);
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next() && rs.getBoolean(1)).as("published_at was marked").isTrue();
            }
        }
    }

    @Test
    void theAppRoleCannotRewriteAnyOtherColumn() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString())) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement rewrite = connection.prepareStatement(
                                "update public.outbox set record_key = 'taken over' where id = ?")) {
                            rewrite.setLong(1, rowA);
                            rewrite.executeUpdate();
                        }
                    })
                    .withMessageContaining("permission denied");
        }
    }

    @Test
    void theAppRoleCannotDeleteAnOutboxRow() throws SQLException {
        try (Connection connection = appRole(TENANT_A.toString())) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement remove = connection.prepareStatement(
                                "delete from public.outbox where id = ?")) {
                            remove.setLong(1, rowA);
                            remove.executeUpdate();
                        }
                    })
                    .withMessageContaining("permission denied");
        }
    }

    @Test
    void withoutTheTenantSettingEveryStatementFailsClosed() throws SQLException {
        try (Connection connection = appRole(null)) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (PreparedStatement read = connection.prepareStatement("select id from public.outbox");
                                ResultSet rs = read.executeQuery()) {
                            rs.next();
                        }
                    })
                    .withMessageContaining("app.tenant_id");
        }
    }
}
