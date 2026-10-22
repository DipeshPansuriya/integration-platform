package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T141 / T-147 Release 1 security gate, suite S2 (TenantIsolation), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityGateS2TenantIsolationContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static PlatformTestWiring.RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean GRANTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    void grantApplicationRole() throws SQLException {
        // the context (Flyway) is up before the first test method; the app role exists from then on
        if (GRANTED.compareAndSet(false, true)) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        }
    }

    /** The restricted application login the product connects as: no superuser, no BYPASSRLS, not the table owner. */
    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), login.username(), login.password());
    }

    /** Every public table under row-level security in the full product schema (all modules' migrations). */
    private static List<String> rlsTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement sql = owner.createStatement();
                ResultSet rs = sql.executeQuery("select c.relname from pg_class c join pg_namespace n on n.oid ="
                        + " c.relnamespace where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity"
                        + " order by 1")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * S2-A1: Horizontal sweep, every admin endpoint family.
     * Expected: 403 or 404 per the API's own convention, 0 rows of B returned; response bodies and error messages name no B data (IDOR pack: monitor information leaks)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T157 is on main (TenantBSeed) but lives in platform-e2e, unreachable from platform-app; HTTP sweep needs a body in platform-e2e or ForgedTenantDenialSuiteTest reuse (T141 gate case S2-A1)")
    void s2_a1_horizontal_sweep_every_admin_endpoint_family() {
        fail("S2-A1 body lands with T157");
    }

    /**
     * S2-A2: Vertical sweep across roles.
     * Expected: All 403 (matches S1-A7; re-run as the S2 harness because the pack's vertical step uses substituted ids, not only role mismatch)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T157 is on main (TenantBSeed) but lives in platform-e2e, unreachable from platform-app; HTTP sweep needs a body in platform-e2e or ForgedTenantDenialSuiteTest reuse (T141 gate case S2-A2)")
    void s2_a2_vertical_sweep_across_roles() {
        fail("S2-A2 body lands with T157");
    }

    /**
     * S2-A3: Method and parameter tampering (IDOR pack bypass set).
     * Expected: No bypass: the tenant filter never depends on path shape or parameter order; every variant errors or 404s inside A
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T157 is on main (TenantBSeed) but lives in platform-e2e, unreachable from platform-app; HTTP sweep needs a body in platform-e2e or ForgedTenantDenialSuiteTest reuse (T141 gate case S2-A3)")
    void s2_a3_method_and_parameter_tampering() {
        fail("S2-A3 body lands with T157");
    }

    /**
     * S2-A4: RLS/GUC control 1 - no session-scope stamping (ADR-007 D6).
     * Expected: Session-scope stamping refuses or has no effect on policies (only transaction-scoped stamping from the bound tenant); the query errors or stays A-bound
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: no external dependency left (T142 harness is on main); body not yet written - needs the real stamping transaction manager (TenantContext bound, then assert the setting is empty after commit); raw-SQL RLS is covered by platform-tenancy RowLevelSecurityGateTest (T141 gate case S2-A4)")
    void s2_a4_rls_guc_control_1_no_session() {
        fail("S2-A4 body lands with T142");
    }

    /**
     * S2-A5: RLS control 2 - autocommit fails closed.
     * Expected: Each errors (empty app.tenant_id → error-deny), returns no rows
     */
    @Test
    void s2_a5_rls_control_2_autocommit_fails_closed() throws SQLException {
        List<String> tables = rlsTables();
        assertThat(tables).as("the full schema has RLS tables to sweep").hasSizeGreaterThan(10);
        List<String> leaked = new ArrayList<>();
        try (Connection app = appConnection()) {
            assertThat(app.getAutoCommit()).isTrue();
            for (String table : tables) {
                // autocommit and a fresh session: no transaction-scoped app.tenant_id exists, so the policy must error
                try (Statement sql = app.createStatement(); ResultSet rs = sql.executeQuery("select * from public.\""
                        + table + "\"")) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    leaked.add(table + " returned " + rows + " rows without erroring");
                } catch (SQLException expected) {
                    assertThat(expected.getSQLState()).as(table).isIn("42704", "22P02", "42501");
                }
            }
        }
        assertThat(leaked).as("tables that answered an unstamped autocommit read").isEmpty();
    }

    /**
     * S2-A6: RLS control 4 - pooled-connection reuse.
     * Expected: 0 cross reads; evidence via the existing cross-tenant denial JUnit suite re-run
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T157 and T158 are on main; body not yet written - needs the job-path case (L-6) on the db-scheduler handler; DB-level pooled reuse is covered by platform-tenancy RowLevelSecurityGateTest.settingNeverLeaksAcrossPooledConnections (T141 gate case S2-A6)")
    void s2_a6_rls_control_4_pooled_connection_reuse() {
        fail("S2-A6 body lands with T157");
    }

    /**
     * S2-A7: Composite-FK rejection (ADR-013 rules 1-3).
     * Expected: PostgreSQL refuses via composite FK ((tenant_id, id)), HTTP 4xx to the caller, no row
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T157 is on main but a behavioural insert needs seeded mapping/contract rows (platform-e2e TenantBSeed / admin API); composite FKs exist in V2_2_730__mapping_versions.sql; body not yet written (T141 gate case S2-A7)")
    void s2_a7_composite_fk_rejection() {
        fail("S2-A7 body lands with T157");
    }

    /**
     * S2-A8: Immutable-version tamper (ADR-013 conformance 1).
     * Expected: All fail with 42501 / guard violation; grants show SELECT, INSERT only for mapping_version
     */
    @Test
    void s2_a8_immutable_version_tamper() throws SQLException {
        try (Connection app = appConnection()) {
            app.setAutoCommit(false);
            for (String tamper : List.of(
                    "update public.mapping_version set compiled_expression = 'tampered'",
                    "update public.mapping_version set content_sha256 = decode(repeat('00', 32), 'hex')",
                    "delete from public.mapping_version",
                    "truncate public.mapping_version")) {
                try (Statement sql = app.createStatement()) {
                    // a rolled-back transaction drops the transaction-local stamp: stamp again for every attempt
                    sql.execute("select set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true)");
                }
                assertThatThrownBy(() -> {
                    try (Statement sql = app.createStatement()) {
                        sql.execute(tamper);
                    }
                }).as(tamper).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(tamper).isEqualTo("42501"));
                app.rollback();
            }
        }
        TreeSet<String> privileges = new TreeSet<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement sql = owner.createStatement();
                ResultSet rs = sql.executeQuery("select privilege_type from information_schema.role_table_grants"
                        + " where table_schema = 'public' and table_name = 'mapping_version' and grantee ="
                        + " 'ipaas_app'")) {
            while (rs.next()) {
                privileges.add(rs.getString(1));
            }
        }
        assertThat(privileges).as("grants of the application role on mapping_version")
                .containsExactly("INSERT", "SELECT");
    }
}
