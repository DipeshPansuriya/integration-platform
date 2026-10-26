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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
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

    /** The worker-style tenant binding the application's composite TenantContext composes (as a stage consumer's). */
    @TestConfiguration
    static class BindingConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        GateFixture.BoundTenant gateBoundTenant(
                @org.springframework.beans.factory.annotation.Qualifier("requestTenantContext")
                io.integrationplatform.tenancy.TenantContext request) {
            return new GateFixture.BoundTenant(request);
        }
    }

    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private GateFixture.BoundTenant binding;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DataSource dataSource;

    private GateFixture fixture() throws SQLException {
        GateFixture fixture = new GateFixture(POSTGRES, login, binding, transactions);
        fixture.seedTenants();
        return fixture;
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

    private static List<String> names(String sql) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement sql1 = owner.createStatement();
                ResultSet rs = sql1.executeQuery(sql)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }

    /** Every public relation under row-level security: ordinary tables, partitioned parents and the partitions. */
    private static List<String> rlsTables() throws SQLException {
        return names("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace where"
                + " n.nspname = 'public' and c.relkind in ('r', 'p') and c.relrowsecurity order by 1");
    }

    /**
     * S2-A2: Vertical sweep across roles.
     * Expected: All 403 (matches S1-A7; re-run as the S2 harness because the pack's vertical step uses substituted ids, not only role mismatch)
     */
    @Test
    @Disabled("OPEN (T201): only the machine leg runs (SecurityGateHttpIdentityContainerTest.s1_a7_s2_a2_machine_leg...); the human vertical role sweep has no body (T141 gate case S2-A2)")
    void s2_a2_vertical_sweep_across_roles() {
        fail("S2-A2 body lands with T157");
    }

    /**
     * S2-A4: RLS/GUC control 1 - no session-scope stamping (ADR-007 D6).
     * Expected: Session-scope stamping refuses or has no effect on policies (only transaction-scoped stamping from the bound tenant); the query errors or stays A-bound
     */
    @Test
    void s2_a4_rls_guc_control_1_no_session() throws Exception {
        GateFixture fixture = fixture();
        fixture.insertMessage(GateFixture.TENANT_A, Instant.now(), "a4-A");
        fixture.insertMessage(GateFixture.TENANT_B, Instant.now(), "a4-B");
        // the production manager stamps from the bound tenant, and the policy then shows that tenant only
        assertThat(fixture.inTenantTx(GateFixture.TENANT_A,
                () -> jdbc.sql("select current_setting('app.tenant_id')").query(String.class).single()))
                .isEqualTo(GateFixture.TENANT_A.toString());
        assertThat(fixture.inTenantTx(GateFixture.TENANT_A, () -> jdbc.sql(
                "select count(*) from public.message where business_key = 'a4-B'").query(Long.class).single()))
                .as("tenant B's row as seen from a transaction stamped A").isZero();
        assertThat(fixture.inTenantTx(GateFixture.TENANT_A, () -> jdbc.sql(
                "select count(*) from public.message where business_key = 'a4-A'").query(Long.class).single()))
                .isEqualTo(1L);
        // transaction-scoped only: after a commit and after a rollback no session-level stamp is left on the pooled
        // connections, so a later statement outside a stamped transaction cannot ride an earlier tenant (S2-A4)
        assertThatThrownBy(() -> fixture.inTenantTx(GateFixture.TENANT_A, () -> {
            throw new IllegalStateException("rolled back on purpose");
        })).isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < 6; i++) {
            try (Connection pooled = dataSource.getConnection(); Statement sql = pooled.createStatement();
                    ResultSet rs = sql.executeQuery("select current_setting('app.tenant_id', true)")) {
                rs.next();
                assertThat(rs.getString(1)).as("session value on pooled connection %d after the stamped tx", i)
                        .isIn(null, "");
            }
        }
    }

    /**
     * S2-A5: RLS control 2 - autocommit fails closed.
     * Expected: Each errors (empty app.tenant_id → error-deny), returns no rows
     */
    @Test
    void s2_a5_rls_control_2_autocommit_fails_closed() throws SQLException {
        String app = login.username();
        List<String> tables = rlsTables();
        assertThat(tables).as("the full schema has RLS relations to sweep").hasSizeGreaterThan(10);
        // the six partitioned parents are in the sweep (a relkind 'r' filter used to skip them) ...
        assertThat(tables).contains("message", "payload", "delivery", "delivery_attempt", "audit_event",
                "message_stage_timing");
        assertThat(names("select c.relname from pg_class c where c.relkind = 'p' and c.relnamespace ="
                + " 'public'::regnamespace and not c.relrowsecurity")).as("partitioned parents without RLS").isEmpty();
        // the enforcement conditions behind the error code, on EVERY table: a real tenant policy, and a role RLS binds
        assertThat(names("select c.relname from pg_class c where c.relkind in ('r', 'p') and c.relrowsecurity and"
                + " c.relnamespace = 'public'::regnamespace and not exists (select 1 from pg_policy p where p.polrelid"
                + " = c.oid and p.polpermissive and p.polcmd in ('*', 'r') and pg_get_expr(p.polqual, p.polrelid) like"
                + " '%current_setting(''app.tenant_id''%')"))
                .as("RLS tables with no permissive read policy keyed on app.tenant_id").isEmpty();
        assertThat(names("select rolname from pg_roles where rolname in ('" + app + "', 'ipaas_app') and (rolsuper"
                + " or rolbypassrls)")).as("application roles that bypass RLS").isEmpty();
        assertThat(names("select c.relname from pg_class c where c.relkind in ('r', 'p') and c.relrowsecurity and"
                + " c.relnamespace = 'public'::regnamespace and not c.relforcerowsecurity and pg_get_userbyid(c.relowner)"
                + " in ('" + app + "', 'ipaas_app')")).as("RLS tables the application role owns without FORCE").isEmpty();
        // no tenant-keyed relation (partitions included) is readable by the application role with RLS off
        assertThat(names("select c.relname from pg_class c where c.relkind in ('r', 'p') and c.relnamespace ="
                + " 'public'::regnamespace and not c.relrowsecurity and exists (select 1 from pg_attribute a where"
                + " a.attrelid = c.oid and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped) and"
                + " has_table_privilege('" + app + "', c.oid, 'SELECT')"))
                .as("tenant-keyed relations readable by the application role with row-level security off").isEmpty();
        List<String> leaked = new ArrayList<>();
        List<String> swept = new ArrayList<>();
        try (Connection appConn = appConnection()) {
            assertThat(appConn.getAutoCommit()).isTrue();
            for (String table : tables) {
                boolean readable = names("select 'x' where has_table_privilege('" + app + "', 'public.\"" + table
                        + "\"', 'SELECT')").size() == 1;
                if (!readable) {
                    continue; // no SELECT grant: refused by privilege, and the policy conditions above still hold
                }
                swept.add(table);
                // autocommit and a fresh session: no transaction-scoped app.tenant_id exists, so the POLICY must
                // error. 42501 would be a missing grant, not row-level security, so it does not count here.
                try (Statement sql = appConn.createStatement(); ResultSet rs = sql.executeQuery("select * from"
                        + " public.\"" + table + "\"")) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    leaked.add(table + " returned " + rows + " rows without erroring");
                } catch (SQLException expected) {
                    assertThat(expected.getSQLState()).as(table).isIn("42704", "22P02");
                }
            }
        }
        assertThat(leaked).as("tables that answered an unstamped autocommit read").isEmpty();
        assertThat(swept).as("RLS relations readable by the application role, each proven fail-closed")
                .contains("message", "payload", "delivery").hasSizeGreaterThan(10);
    }

    /**
     * S2-A6: RLS control 4 - pooled-connection reuse.
     * Expected: 0 cross reads; evidence via the existing cross-tenant denial JUnit suite re-run
     */
    @Test
    void s2_a6_rls_control_4_pooled_connection_reuse() throws Exception {
        GateFixture fixture = fixture();
        for (int i = 0; i < 3; i++) {
            fixture.insertMessage(GateFixture.TENANT_A, Instant.now(), "a6-A-" + i);
        }
        for (int i = 0; i < 5; i++) {
            fixture.insertMessage(GateFixture.TENANT_B, Instant.now(), "a6-B-" + i);
        }
        // worker path: 8 threads alternate the two tenants over the one pool; every transaction must see its own
        // rows and its own stamp only, whatever connection (and whatever previous tenant) it inherits
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                UUID tenant = i % 2 == 0 ? GateFixture.TENANT_A : GateFixture.TENANT_B;
                long expected = tenant.equals(GateFixture.TENANT_A) ? 3 : 5;
                results.add(threads.submit(() -> fixture.inTenantTx(tenant, () -> {
                    long rows = jdbc.sql("select count(*) from public.message where business_key like 'a6-%'")
                            .query(Long.class).single();
                    String stamp = jdbc.sql("select current_setting('app.tenant_id')").query(String.class).single();
                    return rows == expected && stamp.equals(tenant.toString()) ? null
                            : tenant + " saw " + rows + " rows, stamp " + stamp;
                })));
            }
            List<String> crossed = new ArrayList<>();
            for (Future<String> result : results) {
                String problem = result.get(60, TimeUnit.SECONDS);
                if (problem != null) {
                    crossed.add(problem);
                }
            }
            assertThat(crossed).as("transactions that saw another tenant's rows or stamp").isEmpty();
        } finally {
            threads.shutdownNow();
        }
        // and a connection that served a tenant before never answers a transaction with no tenant bound
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> jdbc.sql("select count(*) from public.message").query(Long.class).single()))
                .satisfies(e -> assertThat(GateFixture.sqlState(e)).isIn("42704", "22P02", "42501"));
    }

    /**
     * S2-A7: Composite-FK rejection (ADR-013 rules 1-3).
     * Expected: PostgreSQL refuses via composite FK ((tenant_id, id)), HTTP 4xx to the caller, no row
     */
    @Test
    void s2_a7_composite_fk_rejection() throws Exception {
        GateFixture fixture = fixture();
        UUID systemA = UUID.randomUUID();
        UUID systemB = UUID.randomUUID();
        fixture.ownerExecute("insert into public.system (id, tenant_id, code, name, direction) values (?::uuid,"
                + " ?::uuid, 'a7-a', 'A7 A', 'SOURCE'), (?::uuid, ?::uuid, 'a7-b', 'A7 B', 'SOURCE')",
                systemA.toString(), GateFixture.TENANT_A.toString(), systemB.toString(), GateFixture.TENANT_B.toString());
        String contract = "insert into public.contract (id, tenant_id, system_id, code, role, name) values"
                + " (gen_random_uuid(), ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', 'A7')";
        // control: a same-tenant parent is accepted, so the refusal below is the composite FK and nothing else
        fixture.ownerExecute(contract, GateFixture.TENANT_A.toString(), systemA.toString(), "a7-ok");
        // tenant A's contract naming tenant B's system id: the (tenant_id, system_id) FK refuses it, even for the owner
        assertThatThrownBy(() -> fixture.ownerExecute(contract, GateFixture.TENANT_A.toString(), systemB.toString(),
                "a7-cross"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23503"));
        assertThat(fixture.ownerScalar("select count(*) from public.contract where code = 'a7-cross'")).isEqualTo("0");
        // every FK onto a tenant-scoped parent (other than the tenant directory) is composite
        assertThat(fixture.ownerScalar("select count(*) from pg_constraint c where c.contype = 'f' and"
                + " c.connamespace = 'public'::regnamespace and array_length(c.conkey, 1) = 1 and"
                + " c.confrelid <> 'public.tenant'::regclass and exists (select 1 from pg_attribute a where"
                + " a.attrelid = c.confrelid and a.attname = 'tenant_id' and not a.attisdropped)"))
                .as("single-column FKs onto tenant-scoped parents (each should be a composite (tenant_id, id) FK)")
                .isEqualTo("0");
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
