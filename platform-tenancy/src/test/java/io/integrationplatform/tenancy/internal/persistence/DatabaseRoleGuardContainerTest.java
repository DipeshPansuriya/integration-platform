package io.integrationplatform.tenancy.internal.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * K1 on a real PostgreSQL (ledger row 393): the guard refuses every login that could step round row-level security -
 * the table owner (any schema, also through role membership), a superuser, a BYPASSRLS role - and accepts the intended
 * runtime login (not an owner, member of ipaas_app). Roles get random passwords in memory only (R07).
 */
@Tag("container")
class DatabaseRoleGuardContainerTest {

    private static final String OTHER_SCHEMA = "k1_other";
    private static final String SUPER = "k1_super";
    private static final String BYPASS = "k1_bypass";
    private static final String OTHER_OWNER = "k1_other_owner";
    private static final String OWNER_MEMBER = "k1_owner_member";

    private static final String PASSWORD = UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void roles() throws SQLException {
        RlsTestDatabase db = RlsTestDatabase.get();
        try (Connection admin = db.superuserConnection(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + SUPER + " LOGIN SUPERUSER PASSWORD '" + PASSWORD + "'");
            sql.execute("CREATE ROLE " + BYPASS + " LOGIN BYPASSRLS PASSWORD '" + PASSWORD + "'");
            sql.execute("CREATE ROLE " + OTHER_OWNER + " LOGIN PASSWORD '" + PASSWORD + "'");
            sql.execute("CREATE SCHEMA " + OTHER_SCHEMA + " AUTHORIZATION " + OTHER_OWNER);
            sql.execute("CREATE TABLE " + OTHER_SCHEMA + ".k1_thing (id int)");
            sql.execute("ALTER TABLE " + OTHER_SCHEMA + ".k1_thing OWNER TO " + OTHER_OWNER);
            // a login that owns nothing itself but is a member of the schema owner (pg_has_role, not just current_user)
            sql.execute("CREATE ROLE " + OWNER_MEMBER + " LOGIN PASSWORD '" + PASSWORD + "' IN ROLE " + RlsTestDatabase.OWNER);
        }
    }

    private static DatabaseRoleGuard guardFor(String login, String password) {
        DataSource dataSource = new DriverManagerDataSource(RlsTestDatabase.get().jdbcUrl(), login, password);
        return new DatabaseRoleGuard(dataSource);
    }

    @Test
    @DisplayName("the intended runtime login (not an owner, member of ipaas_app) passes")
    void theRuntimeLoginIsAccepted() {
        DataSource appPool = RlsTestDatabase.get().appPool(1);
        try {
            assertThatCode(() -> new DatabaseRoleGuard(appPool).verifyLoginRole()).doesNotThrowAnyException();
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) appPool).close();
        }
    }

    @Test
    @DisplayName("a login that owns tables (the migrator) is refused, with the K1 message the hosting smoke greps")
    void theTableOwnerIsRefused() {
        assertThatThrownBy(() -> new DatabaseRoleGuard(ownerSource()).verifyLoginRole())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not own a tenant table");
    }

    @Test
    @DisplayName("a superuser is refused")
    void aSuperuserIsRefused() {
        assertThatThrownBy(() -> guardFor(SUPER, PASSWORD).verifyLoginRole())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be a superuser");
    }

    @Test
    @DisplayName("a BYPASSRLS role is refused")
    void aBypassRlsRoleIsRefused() {
        assertThatThrownBy(() -> guardFor(BYPASS, PASSWORD).verifyLoginRole())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not bypass row-level security");
    }

    @Test
    @DisplayName("owning a table in a schema other than public is refused too")
    void ownershipInAnotherSchemaIsRefused() {
        assertThatThrownBy(() -> guardFor(OTHER_OWNER, PASSWORD).verifyLoginRole())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not own a tenant table");
    }

    @Test
    @DisplayName("a member of a role that owns tables is refused (pg_has_role, not only current_user)")
    void membershipOfAnOwnerRoleIsRefused() {
        assertThatThrownBy(() -> guardFor(OWNER_MEMBER, PASSWORD).verifyLoginRole())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not own a tenant table");
    }

    private static DataSource ownerSource() {
        // the Flyway owner logs in with a fixture-held password; borrow its connection through a one-connection source
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource(ownerConnectionQuietly(), true);
    }

    private static Connection ownerConnectionQuietly() {
        try {
            return RlsTestDatabase.get().ownerConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
