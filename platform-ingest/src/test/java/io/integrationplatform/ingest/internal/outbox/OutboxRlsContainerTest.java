package io.integrationplatform.ingest.internal.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.integrationplatform.ingest.port.OutboxTopic;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-007 / E2-F1 "never lost, never mixed": the frozen {@code V2_0_1__outbox.sql} grants and RLS policy, proven as the
 * application role (the owner connection bypasses RLS, so this class opens a restricted {@code ipaas_app} login).
 * Container group (R03, R06). Seeding runs as the Flyway owner; every asserted statement runs on the app-role
 * connection with the session-local {@code app.tenant_id} setting.
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
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestInstance(Lifecycle.PER_CLASS)
class OutboxRlsContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IngestTestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-4000-8000-000000000001");

    @Autowired
    private JdbcClient jdbcClient;

    private long rowA;
    private long rowB;

    @BeforeAll
    void seedAsOwner() {
        jdbcClient.sql("insert into public.tenant (id, code, status) values (:id::uuid, 'rls-a', 'ACTIVE')")
                .param("id", TENANT_A.toString()).update();
        jdbcClient.sql("insert into public.tenant (id, code, status) values (:id::uuid, 'rls-b', 'ACTIVE')")
                .param("id", TENANT_B.toString()).update();
        rowA = seedOutboxRow(TENANT_A, "rls/tenant-a-row");
        rowB = seedOutboxRow(TENANT_B, "rls/tenant-b-row");
    }

    /** Owner-side direct insert (the fixture the relay would have written): RLS and grants do not apply to it. */
    private long seedOutboxRow(UUID tenant, String recordKey) {
        jdbcClient.sql("""
                insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket)
                values (:tenant_id::uuid, :topic, :aggregate_id::uuid, :record_key, 7)""")
                .params(Map.of("tenant_id", tenant.toString(),
                        "topic", OutboxTopic.MESSAGE_ACCEPTED.topicName(),
                        "aggregate_id", MESSAGE_ID.toString(),
                        "record_key", recordKey))
                .update();
        return jdbcClient.sql("select id from public.outbox where record_key = :record_key")
                .param("record_key", recordKey).query(Long.class).single();
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
