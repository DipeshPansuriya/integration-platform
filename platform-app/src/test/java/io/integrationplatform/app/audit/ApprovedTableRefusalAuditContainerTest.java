package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.system.application.ApprovedTableService;
import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T209 item 4 (L19, Karan ruling 2026-10-23): the {@code DB_TABLE_APPROVAL_REFUSED} event, written by the real
 * {@link ApprovedTableService} in its own transaction, reaches {@code public.audit_event} through the real listener,
 * and is readable only under the owning tenant's row-level security. The unit test
 * ({@code ApprovedTableRefusalAuditTest}) proves the event is published; this proves it lands in the ledger.
 *
 * <pre>
 * Scenario: A wide-account refusal is durable and tenant-scoped
 *   Given a PENDING_APPROVAL table request of tenant A and a width check that refuses the account
 *   When a different approver of tenant A approves it
 *   Then the answer is a 409 and the row stays PENDING_APPROVAL
 *   And tenant A reads exactly one DB_TABLE_APPROVAL_REFUSED row, with the closed code and no account or host text
 *   And tenant B reads none
 * </pre>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovedTableRefusalAuditContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("0000009a-0000-4000-8000-00000000009a");
    private static final UUID TENANT_B = UUID.fromString("0000009b-0000-4000-8000-00000000009b");
    private static final UUID SYSTEM = UUID.fromString("0000009a-0000-4000-8000-0000000000a1");
    private static final UUID ROW = UUID.fromString("0000009a-0000-4000-8000-0000000000a2");
    private static final UUID REQUESTER = UUID.fromString("0000009a-0000-4000-8000-0000000000a3");
    private static final UUID APPROVER = UUID.fromString("0000009a-0000-4000-8000-0000000000a4");
    private static final String SETTINGS = "{\"host\":\"db.invalid\",\"port\":5432,\"database\":\"t209\","
            + "\"user\":\"t209_narrow\",\"table\":\"t209_target\"}";
    private static final String WIDE = "DB_ACCOUNT_TOO_WIDE: the account \"svc_secret_login\" holds more than INSERT and"
            + " SELECT on \"orders_in\" or holds account-level or other-table rights (DELETE, SUPERUSER,"
            + " drop table x, host=10.1.2.3); grant the restricted account only INSERT and SELECT";

    private static PlatformTestWiring.RestrictedLogin login;

    @Autowired
    private ApprovedTableService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestBoundTenantContext boundTenant;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        @Primary
        TestBoundTenantContext testBoundTenantContext() {
            return new TestBoundTenantContext();
        }

        /** The partner-database probe is the one thing faked: it refuses the account as too wide. */
        @Bean
        @Primary
        DbAccountWidthCheck refusingWidthCheck() {
            return (systemId, settings) -> Optional.of(WIDE);
        }
    }

    static final class TestBoundTenantContext implements TenantContext {

        private final ThreadLocal<UUID> bound = new ThreadLocal<>();

        void bind(UUID tenantId) {
            bound.set(tenantId);
        }

        void unbind() {
            bound.remove();
        }

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(bound.get());
        }
    }

    private void seed() throws SQLException {
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            execute(owner, "insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 't209-a', 'ACTIVE', 'Etc/UTC'),"
                    + " ('" + TENANT_B + "', 't209-b', 'ACTIVE', 'Etc/UTC') on conflict (id) do nothing");
            execute(owner, "insert into public.system (id, tenant_id, code, name, direction) values"
                    + " ('" + SYSTEM + "', '" + TENANT_A + "', 't209-db', 'T209 DB', 'TARGET')");
            execute(owner, "update public.system set type = 'DB', settings = '" + SETTINGS + "'::jsonb"
                    + " where id = '" + SYSTEM + "'");
            execute(owner, "insert into public.db_target_approved_table (id, tenant_id, system_id, table_name, status,"
                    + " requested_by, target_hash) values ('" + ROW + "', '" + TENANT_A + "', '" + SYSTEM
                    + "', 't209_target', 'PENDING_APPROVAL', '" + REQUESTER + "',"
                    + " public.db_target_fingerprint('" + SETTINGS + "'::jsonb))");
        }
    }

    private static void execute(Connection owner, String sql) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static String ownerScalar(String sql) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement statement = owner.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private <T> T underTenant(UUID tenant, Supplier<T> work) {
        boundTenant.bind(tenant);
        try {
            return work.get();
        } finally {
            boundTenant.unbind();
        }
    }

    private List<String> refusalRows(UUID tenant) {
        return underTenant(tenant, () -> new TransactionTemplate(transactionManager).execute(status -> jdbc.query(
                "select actor || '|' || target_ids::text || '|' || coalesce(before_state, '') || '|'"
                        + " || coalesce(after_state, '') from public.audit_event where action = ?",
                (rs, n) -> rs.getString(1), "DB_TABLE_APPROVAL_REFUSED")));
    }

    @Test
    void aWideAccountRefusalIsDurableInAuditEventAndReadableOnlyByItsTenant() throws Exception {
        seed();

        assertThatThrownBy(() -> underTenant(TENANT_A,
                () -> service.decide(SYSTEM.toString(), ROW.toString(), "APPROVE", APPROVER)))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.body().toString()).as("the answer carries no stored host or login")
                            .doesNotContain("db.invalid").doesNotContain("t209_narrow");
                });

        List<String> underA = refusalRows(TENANT_A);
        assertThat(underA).as("tenant A reads exactly one refusal row from the ledger").hasSize(1);
        String row = underA.getFirst();
        assertThat(row).startsWith(APPROVER + "|")
                .contains("system:" + SYSTEM).contains("db_table_approval:" + ROW)
                .contains("\"status\":\"PENDING_APPROVAL\"")
                .contains("\"refusal\":\"DB_ACCOUNT_TOO_WIDE\"").contains("\"table\":\"t209_target\"")
                .contains("DELETE").contains("SUPERUSER");
        assertThat(row).as("no account, host, SQL or driver text reaches the ledger")
                .doesNotContain("svc_secret_login").doesNotContain("10.1.2.3").doesNotContain("drop table")
                .doesNotContain("grant the restricted").doesNotContain("db.invalid")
                .doesNotContain("t209_narrow");
        assertThat(refusalRows(TENANT_B)).as("RLS: tenant B reads none of tenant A's refusals").isEmpty();
        assertThat(ownerScalar("select count(*) || '@' || min(tenant_id::text) from public.audit_event"
                + " where action = 'DB_TABLE_APPROVAL_REFUSED'"))
                .as("the owner sees the one durable row, stamped with tenant A").isEqualTo("1@" + TENANT_A);
        assertThat(ownerScalar("select status from public.db_target_approved_table where id = '" + ROW + "'"))
                .as("the refused row stays pending").isEqualTo("PENDING_APPROVAL");
    }
}
