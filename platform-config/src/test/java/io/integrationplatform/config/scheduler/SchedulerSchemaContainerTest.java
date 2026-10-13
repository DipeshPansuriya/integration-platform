package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 (ADR-012 section 4): the exemption register's schema test. The five platform-scoped scheduler tables live in
 * their own schema {@code scheduler} - NOT public - with no row-level security (no row of them is tenant data: the
 * jobs are platform-wide), access by grant only: the application role {@code ipaas_app} reads and writes, PUBLIC
 * holds nothing, and a login outside the application role reads nothing. Each register entry has the test that it
 * carries no {@code tenant_id} column; adding a table to this register needs an ADR. The exact RLS/grant shape
 * follows the console-session precedent (platform-tenancy's ConsoleSessionMigrationContainerTest; V2_2_1400's
 * comment block points back to it). Container group (R03); needs only PostgreSQL.
 */
@Tag("container")
class SchedulerSchemaContainerTest {

    /** The platform's pinned PostgreSQL tag (TestImages.POSTGRES twin; this module imports no other module's tests). */
    static final String POSTGRES_TAG = "postgres:17.11-alpine";

    /** The exemption register of ADR-012 section 4: exactly these five tables. */
    static final List<String> REGISTER = List.of(
            "scheduled_tasks", "job_definition", "job_run", "job_alert", "operator_credential");

    static final String APPLICATION_ROLE = "ipaas_app";

    private static PostgreSQLContainer postgres;
    private static String outsiderUser;
    private static String outsiderPassword;

    @BeforeAll
    static void deployMigrations() throws SQLException {
        postgres = new PostgreSQLContainer(POSTGRES_TAG);
        postgres.start();
        // the migrations whose table shapes must hold together: the beside tenant directory's (it creates ipaas_app
        // and the tenant gates) and this module's (the scheduler schema) - version-ordered, applied by the real
        // Flyway as the container's owner user, the way a deployment does
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String tenancy = basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration")
                .normalize().toString().replace('\\', '/');
        String config = basedir.resolve("src/main/resources/db/migration").normalize().toString()
                .replace('\\', '/');
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + tenancy, "filesystem:" + config)
                .load()
                .migrate();
        // the outsider: a login with no grants at all, so the grant-only shape is proven positively
        long seed = System.nanoTime();
        outsiderUser = "scheduler_outsider_" + Long.toUnsignedString(seed, 36);
        outsiderPassword = "out" + Long.toUnsignedString(seed + 1, 32);
        try (Connection admin = ownerConnection(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + outsiderUser + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE"
                    + " PASSWORD '" + outsiderPassword + "'");
        }
    }

    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void everyExemptionRegisterTableHasNoTenantIdColumn() throws SQLException {
        try (Connection admin = ownerConnection(); Statement sql = admin.createStatement()) {
            assertThat(text(sql, "select coalesce(string_agg(c.relname, ',' order by c.relname), '')"
                    + " from pg_attribute a, pg_class c, pg_namespace n"
                    + " where c.relnamespace = n.oid and a.attrelid = c.oid"
                    + " and n.nspname = 'scheduler' and c.relkind = 'r' and a.attname = 'tenant_id'"))
                    .as("no register table carries a tenant_id column").isEmpty();
        }
    }

    @Test
    void theSchedulerTablesHaveNoRowLevelSecurityAndOnlyTheApplicationRoleIsGranted() throws SQLException {
        try (Connection admin = ownerConnection(); Statement sql = admin.createStatement()) {
            assertThat(text(sql, "select string_agg(relname || ':' || relrowsecurity, ',' order by relname)"
                    + " from pg_class where relnamespace = 'scheduler'::regnamespace and relkind = 'r'"))
                    .isEqualTo("job_alert:false,job_definition:false,job_run:false,"
                            + "operator_credential:false,scheduled_tasks:false");
            assertThat(text(sql, "select string_agg(distinct r.rolname, ',' order by r.rolname)"
                    + " from pg_class c, aclexplode(c.relacl) a join pg_roles r on r.oid = a.grantee"
                    + " where c.relnamespace = 'scheduler'::regnamespace and c.relkind = 'r'"
                    + " and r.rolname <> '" + postgres.getUsername() + "'"))
                    .as("the application role is the only non-owner grantee").isEqualTo(APPLICATION_ROLE);
            assertThat(text(sql, "select string_agg(rolname, ',' order by rolname) from pg_roles r, pg_namespace n"
                    + " where n.nspname = 'scheduler' and has_schema_privilege(r.oid, n.oid, 'USAGE')"
                    + " and not r.rolname like 'pg_%' and r.rolname <> '" + postgres.getUsername() + "'"))
                    .as("only the application role may use the schema").isEqualTo(APPLICATION_ROLE);
            assertThat(text(sql, "select count(*) from pg_class c, aclexplode(c.relacl) a"
                    + " where c.relnamespace = 'scheduler'::regnamespace and a.grantee = 0"))
                    .as("PUBLIC holds nothing").isEqualTo("0");
        }
    }

    @Test
    void aLoginOutsideTheApplicationRoleCannotReadSchedulerTables() throws SQLException {
        try (Connection outsider = DriverManager.getConnection(postgres.getJdbcUrl(), outsiderUser, outsiderPassword);
                Statement sql = outsider.createStatement()) {
            assertThatThrownBy(() -> sql.executeQuery("select count(*) from scheduler.job_run"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
    }

    private static String text(Statement sql, String query) throws SQLException {
        try (var rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}