package io.integrationplatform.tenancy.internal.rls;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.APP_ROLE;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USER_ALICE;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USER_BOB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-099 (ADR-007 Decision 5): roles are rows of the tenant-scoped, row-level-secured {@code public.role_grant} table
 * (V2_2_6), never identity-provider roles. Raw SQL as the restricted application login, stamped exactly as the
 * application stamps (transaction-local {@code set_config}). Container group (R03), one Docker suite at a time (R06).
 */
@Tag("container")
class RoleGrantGateTest {

    private static final String PERMISSION_DENIED = "42501";
    private static final String UNDEFINED_PARAMETER = "42704";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    private static RlsTestDatabase db;

    @BeforeAll
    static void grants() throws SQLException {
        db = RlsTestDatabase.get();
        try (Connection owner = db.ownerConnection()) {
            grant(owner, TENANT_A, USER_ALICE, "CONFIGURATOR");
            grant(owner, TENANT_A, USER_ALICE, "APPROVER");
            grant(owner, TENANT_B, USER_BOB, "TENANT_ADMIN");
        }
    }

    @Test
    @DisplayName("A tenant-bound transaction sees only its own tenant's grants (no tenant predicate in the SQL)")
    void rlsReturnsOnlyTheBoundTenantsGrants() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);
            assertThat(texts(app, "select user_id::text || ':' || role from public.role_grant order by role"))
                    .containsExactly(USER_ALICE + ":APPROVER", USER_ALICE + ":CONFIGURATOR");
            app.rollback();
            stamp(app, TENANT_B);
            assertThat(texts(app, "select user_id::text || ':' || role from public.role_grant"))
                    .containsExactly(USER_BOB + ":TENANT_ADMIN");
            app.rollback();
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("No tenant stamped: the read errors (error-deny), it never returns rows")
    void anUnstampedReadFailsClosed() throws SQLException {
        try (Connection app = db.appConnection()) {
            assertThatThrownBy(() -> texts(app, "select role from public.role_grant"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNDEFINED_PARAMETER));
        }
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("The application role may only read grants: insert, update and delete are denied by grant")
    void theApplicationRoleCannotWriteGrants() throws SQLException {
        for (String write : List.of(
                "insert into public.role_grant (tenant_id, user_id, role) values ('" + TENANT_A + "', '"
                        + USER_ALICE + "', 'TENANT_ADMIN')",
                "update public.role_grant set role = 'TENANT_ADMIN'",
                "delete from public.role_grant")) {
            try (Connection app = db.appConnection()) {
                app.setAutoCommit(false);
                stamp(app, TENANT_A);
                assertThatThrownBy(() -> app.createStatement().execute(write))
                        .as(write).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
                app.rollback();
            }
        }
        try (Connection admin = db.superuserConnection(); PreparedStatement grants = admin.prepareStatement(
                "select string_agg(a.privilege_type, ',' order by a.privilege_type) from pg_class c,"
                        + " aclexplode(c.relacl) a where c.relname = 'role_grant'"
                        + " and c.relnamespace = 'public'::regnamespace"
                        + " and a.grantee = (select oid from pg_roles where rolname = ?)")) {
            grants.setString(1, APP_ROLE);
            try (ResultSet rs = grants.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("SELECT");
            }
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("Closed role set, one row per (tenant, user, role), and a grant names a user OF ITS tenant")
    void theSchemaRefusesBadGrants() throws SQLException {
        try (Connection owner = db.ownerConnection()) {
            assertThatThrownBy(() -> grant(owner, TENANT_A, USER_ALICE, "SUPERUSER"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION));
            assertThatThrownBy(() -> grant(owner, TENANT_A, USER_ALICE, "CONFIGURATOR"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
            // bob is a user of tenant B: a tenant-A grant for him breaks the composite reference
            assertThatThrownBy(() -> grant(owner, TENANT_A, USER_BOB, "READ_ONLY"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION));
            assertThatThrownBy(() -> grant(owner, TENANT_A, UUID.randomUUID(), "READ_ONLY"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION));
        }
    }

    @Test
    @DisplayName("Row-level security is on (not FORCE'd, like every directory table) with the one error-deny policy")
    void rlsIsOn() throws SQLException {
        try (Connection admin = db.superuserConnection()) {
            assertThat(texts(admin, "select relrowsecurity || ':' || relforcerowsecurity from pg_class"
                    + " where relname = 'role_grant' and relnamespace = 'public'::regnamespace"))
                    .containsExactly("true:false");
            assertThat(texts(admin, "select policyname || ':' || cmd || ':' || qual from pg_policies"
                    + " where tablename = 'role_grant'"))
                    .singleElement().asString()
                    .startsWith("role_grant_tenant_isolation:ALL:")
                    .contains("current_setting('app.tenant_id'");
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static void grant(Connection owner, UUID tenant, UUID user, String role) throws SQLException {
        try (PreparedStatement insert = owner.prepareStatement(
                "insert into public.role_grant (tenant_id, user_id, role) values (?::uuid, ?::uuid, ?)")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, user.toString());
            insert.setString(3, role);
            insert.executeUpdate();
        }
    }

    private static void stamp(Connection connection, UUID tenant) throws SQLException {
        try (PreparedStatement set = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            set.setString(1, tenant.toString());
            set.execute();
        }
    }

    private static List<String> texts(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(sql); ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
