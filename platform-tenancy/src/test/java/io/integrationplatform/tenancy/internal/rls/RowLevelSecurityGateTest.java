package io.integrationplatform.tenancy.internal.rls;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.APP_LOGIN;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.APP_ROLE;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.OTHER_ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.OWNER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USERS_OF_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USERS_OF_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USER_ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-006 - the RLS gate on the raw-SQL path (no application filter, no Spring): E1-F2-S2, the ADR-009 directory gate
 * d1-d4, ADR-007 Decision 6 rules 2 and 4, and the T5/T6 grant inspection. Every statement runs as the restricted
 * application login; the tenant setting is stamped exactly as the application stamps it (transaction-local
 * {@code set_config(..., true)}).
 *
 * <p>Empty-setting form (ADR-009 Decision 5, d2): <b>statement error</b> - the policy casts
 * {@code current_setting('app.tenant_id')} to uuid; an unset parameter fails with 42704 and an empty one (left behind
 * by an earlier transaction-local stamp on the same connection) with 22P02. Either way no row is returned.
 *
 * <p>Container group (R03): runs only with {@code -Pcontainer}, one Docker suite at a time (R06).
 */
@Tag("container")
class RowLevelSecurityGateTest {

    private static final String PERMISSION_DENIED = "42501";
    private static final String UNDEFINED_PARAMETER = "42704";
    private static final String INVALID_TEXT_REPRESENTATION = "22P02";

    private RlsTestDatabase db;

    @BeforeEach
    void database() {
        db = RlsTestDatabase.get();
    }

    // ---- E1-F2-S2 -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("S2: RLS returns only the current tenant's rows (raw SQL, no tenant predicate)")
    void rlsReturnsOnlyTheCurrentTenantsRows() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);

            assertThat(uuids(app, "select tenant_id from app_user")).hasSize(USERS_OF_A).containsOnly(TENANT_A);
            assertThat(uuids(app, "select id from tenant")).containsExactly(TENANT_A);
            app.rollback();
        }
    }

    @Test
    @DisplayName("S2 @negative: an empty tenant setting fails closed - unset parameter (fresh connection)")
    void unsetTenantSettingFailsClosed() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            for (String table : List.of("app_user", "tenant")) {
                assertThatThrownBy(() -> uuids(app, "select id from " + table))
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNDEFINED_PARAMETER));
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("S2 @negative / d2: an empty tenant setting fails closed - emptied by an earlier transaction")
    void emptyTenantSettingFailsClosed() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);
            app.commit();
            for (String table : List.of("app_user", "tenant")) {
                assertThat(text(app, "select current_setting('app.tenant_id')")).isEmpty();
                assertThatThrownBy(() -> uuids(app, "select id from " + table))
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState())
                                .isEqualTo(INVALID_TEXT_REPRESENTATION));
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("S2 @negative @security: the application role cannot disable row-level security")
    void applicationRoleCannotDisableRowLevelSecurity() throws SQLException {
        try (Connection app = db.appConnection()) {
            for (String statement : List.of(
                    "alter table tenant disable row level security",
                    "alter table app_user disable row level security",
                    "alter table app_user no force row level security",
                    "drop policy tenant_dir_self on tenant")) {
                assertRefused(app, statement, PERMISSION_DENIED);
            }
        }
    }

    @Test
    @DisplayName("S2 @negative @security: the application role cannot read with row security off or grant itself BYPASSRLS")
    void applicationRoleCannotBypassRowLevelSecurity() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            try (Statement sql = app.createStatement()) {
                sql.execute("set local row_security = off");
            }
            stamp(app, TENANT_A);
            assertThatThrownBy(() -> uuids(app, "select id from app_user"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
            app.rollback();
            app.setAutoCommit(true);
            assertRefused(app, "alter role " + APP_LOGIN + " bypassrls", PERMISSION_DENIED);
            assertRefused(app, "alter role " + APP_ROLE + " bypassrls", PERMISSION_DENIED);
        }
    }

    // ---- ADR-009 directory gate d1-d4 (E1-F2-S3) ------------------------------------------------------------------

    @Test
    @DisplayName("d1 / S3 @negative: the directory shows only the own row (setting A, select B's row: 0 rows)")
    void directoryShowsOnlyTheOwnRow() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);
            try (PreparedStatement select = app.prepareStatement("select id from tenant where id = ?::uuid")) {
                select.setString(1, TENANT_B.toString());
                assertThat(rows(select)).isZero();
            }
            assertThat(uuids(app, "select id from tenant")).containsExactly(TENANT_A);
            app.rollback();
        }
    }

    @Test
    @DisplayName("d3 / S3 @negative @security: INSERT, UPDATE, DELETE, TRUNCATE on tenant are refused by grant (4 x 42501)")
    void directoryCannotBeWrittenByTheApplication() throws SQLException {
        List<String> writes = List.of(
                "insert into tenant (id, code, status, time_zone) values ('" + UUID.randomUUID()
                        + "'::uuid, 'forged', 'ACTIVE', 'Etc/UTC')",
                "update tenant set status = 'SUSPENDED'",
                "delete from tenant",
                "truncate tenant");
        int refused = 0;
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            for (String write : writes) {
                stamp(app, TENANT_A); // the own tenant is stamped: what refuses the write is the grant, not the policy
                try (Statement sql = app.createStatement()) {
                    sql.execute(write);
                } catch (SQLException e) {
                    if (PERMISSION_DENIED.equals(e.getSQLState())) {
                        refused++;
                    }
                }
                app.rollback();
            }
        }
        assertThat(refused).as("writes refused with SQLSTATE 42501").isEqualTo(4);
    }

    @Test
    @DisplayName("d4 / S3: the login bootstrap returns the narrow projection for the own subject (1 row)")
    void bootstrapReturnsTheNarrowProjectionForTheOwnSubject() throws SQLException {
        try (Connection app = db.appConnection();
                PreparedStatement call = app.prepareStatement("select * from ipaas_login_bootstrap(?, ?)")) {
            call.setString(1, ISSUER);
            call.setString(2, "alice");
            try (ResultSet rs = call.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnName(i));
                }
                assertThat(columns).containsExactly(
                        "user_id", "user_status", "tenant_id", "tenant_code", "tenant_status", "time_zone");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("user_id", UUID.class)).isEqualTo(USER_ALICE);
                assertThat(rs.getString("user_status")).isEqualTo("ACTIVE");
                assertThat(rs.getObject("tenant_id", UUID.class)).isEqualTo(TENANT_A);
                assertThat(rs.getString("tenant_code")).isEqualTo("tenant-a");
                assertThat(rs.getString("tenant_status")).isEqualTo("ACTIVE");
                assertThat(rs.getString("time_zone")).isEqualTo("Asia/Kolkata");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("d4 @negative: foreign, unknown, inactive-user and suspended-tenant subjects return 0 rows")
    void bootstrapReturnsNothingForAForeignOrInactiveSubject() throws SQLException {
        try (Connection app = db.appConnection();
                PreparedStatement call = app.prepareStatement("select * from ipaas_login_bootstrap(?, ?)")) {
            String[][] cases = {
                {OTHER_ISSUER, "alice"}, // the same subject from another issuer is a different identity
                {ISSUER, "mallory"}, // not in the directory
                {ISSUER, "carol"}, // DISABLED user of an ACTIVE tenant
                {ISSUER, "dave"}, // ACTIVE user of a SUSPENDED tenant
            };
            for (String[] pair : cases) {
                call.setString(1, pair[0]);
                call.setString(2, pair[1]);
                assertThat(rows(call)).as("%s / %s", pair[0], pair[1]).isZero();
            }
        }
    }

    @Test
    @DisplayName("T5 @negative: the bootstrap is a pinned-search_path STABLE definer, executable by the application role only")
    void bootstrapFunctionIsNarrowAndExecutableByTheApplicationRoleOnly() throws SQLException {
        try (Connection admin = db.superuserConnection();
                PreparedStatement fn = admin.prepareStatement(
                        "select p.prosecdef, p.provolatile, array_to_string(p.proconfig, ';'), r.rolname,"
                                + " has_function_privilege(?, p.oid, 'EXECUTE'),"
                                + " exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0)"
                                + " from pg_proc p join pg_roles r on r.oid = p.proowner"
                                + " where p.proname = 'ipaas_login_bootstrap'")) {
            fn.setString(1, APP_LOGIN);
            try (ResultSet rs = fn.executeQuery()) {
                assertThat(rs.next()).as("ipaas_login_bootstrap exists").isTrue();
                assertThat(rs.getBoolean(1)).as("SECURITY DEFINER").isTrue();
                assertThat(rs.getString(2)).as("STABLE").isEqualTo("s");
                assertThat(rs.getString(3)).isEqualTo("search_path=pg_catalog, public");
                assertThat(rs.getString(4)).as("owned by the migration owner").isEqualTo(OWNER);
                assertThat(rs.getBoolean(5)).as("the application login may execute").isTrue();
                assertThat(rs.getBoolean(6)).as("PUBLIC holds EXECUTE").isFalse();
                assertThat(rs.next()).as("exactly one overload").isFalse();
            }
        }
        try (Connection outsider = db.outsiderConnection();
                PreparedStatement call = outsider.prepareStatement("select * from ipaas_login_bootstrap(?, ?)")) {
            call.setString(1, ISSUER);
            call.setString(2, "alice");
            assertThatThrownBy(call::executeQuery)
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
        }
    }

    // ---- ADR-007 Decision 6, rules 2 and 4 ------------------------------------------------------------------------

    @Test
    @DisplayName("D6 rule 2: an autocommit select on a tenant table errors")
    void autocommitSelectOnATenantTableErrors() throws SQLException {
        try (Connection app = db.appConnection()) {
            assertThat(app.getAutoCommit()).isTrue();
            for (String table : List.of("app_user", "tenant")) {
                assertThatThrownBy(() -> uuids(app, "select id from " + table)).isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    @DisplayName("D6 rule 4 / S3 @negative: one pooled connection serves tenant A then B and never leaks the setting")
    void settingNeverLeaksAcrossPooledConnections() throws SQLException {
        try (HikariDataSource pool = db.appPool(1)) {
            int backendA;
            try (Connection app = pool.getConnection()) {
                app.setAutoCommit(false);
                backendA = backendPid(app);
                stamp(app, TENANT_A);
                assertThat(uuids(app, "select tenant_id from app_user")).hasSize(USERS_OF_A).containsOnly(TENANT_A);
                app.commit();
            }
            try (Connection app = pool.getConnection()) {
                app.setAutoCommit(false);
                assertThat(backendPid(app)).as("the same pooled connection").isEqualTo(backendA);
                assertThat(text(app, "select current_setting('app.tenant_id', true)"))
                        .as("nothing of tenant A is left when the next transaction starts")
                        .isEmpty();
                stamp(app, TENANT_B);
                assertThat(text(app, "select current_setting('app.tenant_id')")).isEqualTo(TENANT_B.toString());
                assertThat(uuids(app, "select tenant_id from app_user")).hasSize(USERS_OF_B).containsOnly(TENANT_B);
                assertThat(uuids(app, "select id from tenant")).containsExactly(TENANT_B);
                app.commit();
            }
        }
    }

    // ---- T6 grant inspection --------------------------------------------------------------------------------------

    @Test
    @DisplayName("T6 @security: no superuser, no BYPASSRLS, owns no object, no DDL; RLS enabled, not forced")
    void grantInspection() throws SQLException {
        try (Connection admin = db.superuserConnection()) {
            for (String role : List.of(APP_ROLE, APP_LOGIN)) {
                try (PreparedStatement attrs = admin.prepareStatement(
                        "select rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, rolreplication"
                                + " from pg_roles where rolname = ?")) {
                    attrs.setString(1, role);
                    try (ResultSet rs = attrs.executeQuery()) {
                        assertThat(rs.next()).as("role %s exists", role).isTrue();
                        for (int i = 1; i <= 5; i++) {
                            assertThat(rs.getBoolean(i)).as("%s attribute %d", role, i).isFalse();
                        }
                    }
                }
                assertThat(count(admin, "select count(*) from pg_class c join pg_roles r on r.oid = c.relowner"
                        + " where r.rolname = ?", role)).as("%s owns tables/sequences/views", role).isZero();
                assertThat(count(admin, "select count(*) from pg_proc p join pg_roles r on r.oid = p.proowner"
                        + " where r.rolname = ?", role)).as("%s owns functions", role).isZero();
                assertThat(count(admin, "select count(*) from pg_namespace n join pg_roles r on r.oid = n.nspowner"
                        + " where r.rolname = ?", role)).as("%s owns schemas", role).isZero();
                assertThat(count(admin, "select count(*) where has_schema_privilege(?, 'public', 'CREATE')", role))
                        .as("%s may CREATE in schema public", role).isZero();
                assertThat(count(admin,
                        "select count(*) where has_database_privilege(?, current_database(), 'CREATE')", role))
                        .as("%s may CREATE schemas", role).isZero();
            }
            assertThat(count(admin, "select count(*) from pg_roles where rolname = ? and rolcanlogin", APP_ROLE))
                    .as("ipaas_app is a NOLOGIN role").isZero();
            assertThat(text(admin, "select string_agg(relname || ':' || relrowsecurity || ':' || relforcerowsecurity,"
                    + " ',' order by relname) from pg_class where relname in ('tenant', 'app_user')"
                    + " and relnamespace = 'public'::regnamespace"))
                    .isEqualTo("app_user:true:false,tenant:true:false");
            assertThat(text(admin, "select string_agg(tablename || ':' || policyname || ':' || cmd, ','"
                    + " order by tablename) from pg_policies where schemaname = 'public'"))
                    .isEqualTo("app_user:app_user_tenant_isolation:ALL,tenant:tenant_dir_self:ALL");
            assertThat(text(admin, "select string_agg(c.relname || ':' || a.privilege_type, ',' order by c.relname,"
                    + " a.privilege_type) from pg_class c, aclexplode(c.relacl) a"
                    + " where c.relnamespace = 'public'::regnamespace"
                    + " and a.grantee = (select oid from pg_roles where rolname = '" + APP_ROLE + "')"))
                    .as("E1 grants of ipaas_app: SELECT only")
                    .isEqualTo("app_user:SELECT,tenant:SELECT");
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Exactly what the application does at transaction start (ADR-007 Decision 6 rule 1). */
    private static void stamp(Connection connection, UUID tenant) throws SQLException {
        try (PreparedStatement set = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            set.setString(1, tenant.toString());
            set.execute();
        }
    }

    private static List<UUID> uuids(Connection connection, String query) throws SQLException {
        List<UUID> result = new ArrayList<>();
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                result.add(rs.getObject(1, UUID.class));
            }
        }
        return result;
    }

    private static String text(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        return Integer.parseInt(text(connection, "select pg_backend_pid()"));
    }

    private static long count(Connection connection, String query, String parameter) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            sql.setString(1, parameter);
            try (ResultSet rs = sql.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static int rows(PreparedStatement query) throws SQLException {
        int n = 0;
        try (ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                n++;
            }
        }
        return n;
    }

    private static void assertRefused(Connection connection, String statement, String sqlState) {
        assertThatThrownBy(() -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }).as(statement).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(statement).isEqualTo(sqlState));
    }
}
