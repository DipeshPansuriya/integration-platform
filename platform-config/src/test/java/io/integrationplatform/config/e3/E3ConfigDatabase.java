package io.integrationplatform.config.e3;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The E3 lane A container fixture (T-016, T-092, T-093): one PostgreSQL per running suite class, wired the way
 * {@code platform-app}'s {@code PlatformTestWiring} wires every E1 suite - the same fixed environment
 * variable-name pattern, copied here because platform-config cannot depend on that module's test classes (the
 * poms are the lead's seam; the pattern is what R07 fixes, not the file).
 *
 * <ul>
 *   <li>{@code DB_MIGRATION_USERNAME} / {@code DB_MIGRATION_PASSWORD}: the container's owner user, which Flyway
 *       (at deploy time) uses.</li>
 *   <li>{@code DB_URL} / {@code DB_USERNAME} / {@code DB_PASSWORD}: a restricted LOGIN user created here with a
 *       random password and granted {@code ipaas_app} once the role exists (ADR-009); the composed context
 *       connects as this login only.</li>
 *   <li>{@code OIDC_ISSUER_URI} / {@code OIDC_AUDIENCE}: test values; the issuer is never contacted.</li>
 * </ul>
 *
 * <p>ponytail: lane A may not touch the poms, and the armed platform-config pom carries no PostgreSQL JDBC driver
 * and no Flyway, so the suite gates on them instead of dying on an unnamed boot error: every gate names the
 * missing node WITH its owner (the committed RED of the red-first lane). Nothing here stubs a seam to green.
 */
final class E3ConfigDatabase {

    /** The platform's pinned PostgreSQL tag (identical to {@code TestImages.POSTGRES}; this module cannot import it). */
    static final String POSTGRES_TAG = "postgres:17.11-alpine";

    /** The restricted application role the tenancy migration creates (ADR-009; copied from PlatformTestWiring). */
    static final String APPLICATION_ROLE = "ipaas_app";

    /** The Flyway history table every module's deployment writes; the rows-only gate reads it. */
    static final String SCHEMA_HISTORY = "flyway_schema_history";

    /** The OIDC issuer the wiring names; never contacted (the {@code .invalid} registry never resolves). */
    static final URI OIDC_ISSUER = URI.create("https://issuer.e1-suite.invalid/realms/platform");

    static final String OIDC_AUDIENCE = "integration-platform-api";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The one container of a suite class; created on first use (Testcontainers stops it on JVM shutdown). */
    private static PostgreSQLContainer postgres;

    private E3ConfigDatabase() {
    }

    static PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(POSTGRES_TAG);
            postgres.start();
        }
        return postgres;
    }

    // ------------------------------------------------------------------ named pending-integration gates (the RED)

    /** Named failure for the first missing node and its owner - the shape of the chaos suite's requireIntegrated. */
    static void pendingIntegration(String what, String owner) {
        throw new PendingIntegration(what, owner);
    }

    static final class PendingIntegration extends RuntimeException {

        PendingIntegration(String what, String owner) {
            super("pending integration: " + what + " (owner: " + owner + ")");
        }
    }

    /** No PostgreSQL JDBC driver on the test classpath, so no JDBC access can exist yet. */
    static void requirePostgresDriver() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            pendingIntegration("platform-config's test classpath carries no PostgreSQL JDBC driver - the armed pom "
                    + "needs it (test scope) before lane A's suites can reach PostgreSQL", "arjun-fullstack");
        }
    }

    /** No Flyway on the test classpath, so the checked-in migrations cannot be deployed. */
    static void requireFlyway() {
        try {
            Class.forName("org.flywaydb.core.Flyway");
        } catch (ClassNotFoundException e) {
            pendingIntegration("platform-config's test classpath carries no Flyway - the checked-in migrations "
                    + "(the config tables of T-014, owner lane B) cannot be deployed in this module", "arjun-fullstack");
        }
    }

    // ------------------------------------------------------------------ identity wiring (the fixed env names)

    /** A LOGIN user the composed app connects as; its password exists only in memory for this run. */
    record RestrictedLogin(String username, String password) {

        @Override
        public String toString() {
            return "RestrictedLogin[" + username + "]";
        }
    }

    private static RestrictedLogin login;
    private static final Map<String, SeededTenant> SEEDED = new LinkedHashMap<>();

    static RestrictedLogin restrictedLogin() {
        requirePostgresDriver();
        return login; // set by seedDirectory(); null after a missed seed - its own named gate covers that
    }

    /**
     * Seeds the platform the way a deployment does: the checked-in migrations applied as the owner (real Flyway,
     * reflectively - see {@link ReflectFlyway} for why), then the restricted login created and granted
     * {@code ipaas_app}, then tenants alpha and bravo seeded beside each other (the E1 denial-suite shape).
     */
    static RestrictedLogin seedDirectory() {
        requirePostgresDriver(); // named RED before any JDBC attempt
        requireFlyway();         // named RED next

        applyCheckedInMigrations();

        try {
            login = createRestrictedLogin();
            grantApplicationRole();
            try (Connection owner = ownerConnection()) {
                SEEDED.clear();
                SEEDED.put("alpha", seedTenant(owner, "alpha"));
                SEEDED.put("bravo", seedTenant(owner, "bravo"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the directory seed failed", e);
        }
        return login;
    }

    /** A connection as the container's owner user (the Flyway owner): seeding and fixtures only, never the app. */
    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
    }

    private static void grantApplicationRole() throws SQLException {
        executeFormatted("GRANT %I TO %I", APPLICATION_ROLE, login.username());
    }

    private static RestrictedLogin createRestrictedLogin() throws SQLException {
        String username = "app_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        RestrictedLogin created = new RestrictedLogin(username,
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        executeFormatted("CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L",
                created.username(), created.password());
        return created;
    }

    /** DDL cannot take bind parameters, so the statement is built server-side by format() with %I / %L quoting. */
    private static void executeFormatted(String template, String... args) throws SQLException {
        StringBuilder call = new StringBuilder("SELECT format(?");
        for (int i = 0; i < args.length; i++) {
            call.append(", ?");
        }
        call.append(')');
        try (Connection owner = ownerConnection()) {
            String ddl;
            try (PreparedStatement format = owner.prepareStatement(call.toString())) {
                format.setString(1, template);
                for (int i = 0; i < args.length; i++) {
                    format.setString(i + 2, args[i]);
                }
                try (ResultSet rs = format.executeQuery()) {
                    rs.next();
                    ddl = rs.getString(1);
                }
            }
            try (Statement statement = owner.createStatement()) {
                statement.execute(ddl);
            }
        }
    }

    // ------------------------------------------------------------------ the platform's checked-in migrations

    /**
     * The tenant directory's migrations must be deployed for the identity premise to hold, but they are not on
     * this module's classpath (no inter-module dependency on this path in the lead's seam), so the fixture deploys
     * the checked-in SQL where the reactor layout puts it: the beside module's and this module's migration
     * folders, version-ordered, as the owner.
     */
    private static void applyCheckedInMigrations() {
        List<String> locations = new ArrayList<>();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path besideDirectory = basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration");
        if (Files.isDirectory(besideDirectory)) {
            locations.add("filesystem:" + besideDirectory.toString().replace('\\', '/'));
        }
        Path configMigrations = basedir.resolve("src/main/resources/db/migration");
        if (Files.isDirectory(configMigrations)) {
            locations.add("filesystem:" + configMigrations.toString().replace('\\', '/'));
        }
        if (locations.isEmpty()) {
            pendingIntegration("no checked-in migrations to deploy - the config tables of T-014 (owner lane B) do "
                    + "not exist yet", "lane B (T-014)");
        }
        int applied = deployCheckedInMigrations(postgres().getJdbcUrl(), postgres().getUsername(),
                postgres().getPassword(), locations.toArray(String[]::new), SCHEMA_HISTORY);
        // Wave-0 glue (integration idempotency): the suites share one container per JVM, so a later suite's
        // deployment legitimately applies 0 scripts (they are all applied already) - the gate means "nothing is
        // deployed", not "nothing was newly deployed".
        if (applied <= 0 && snapshot().appliedVersionRows().stream().noneMatch(
                row -> row.contains("V1_3_") || row.contains("V2_"))) {
            pendingIntegration("the migrations deployed 0 scripts - the tenant directory or the config tables are "
                    + "missing (owner: lane B T-014 / the lead's composition)", "arjun-fullstack");
        }
    }

    /**
     * The platform's real Flyway, deployed the real way. Wave-0 integration glue (arjun-fullstack): lane A wrote
     * this invocation reflectively while the pom had no Flyway; the arming commit put Flyway 12.4.0 on the test
     * classpath, whose API changed (Flyway 11.x removed {@code locations(String...)}'s old shape around version
     * numbering and {@code migrate()} now returns {@code MigrateResult}), so the reflection is replaced by the
     * direct type-checked call - the same deployments, no reflective state a green run could hide behind.
     */
    static int deployCheckedInMigrations(String url, String user, String password, String[] locations,
            String historyTable) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
                .migrationsExecuted;
    }

    // ------------------------------------------------------------------ the rows-only gate

    /**
     * What "the schema version is unchanged" observes without a fake: the applied version rows of the Flyway
     * history table, and the set of relations the public schema holds. Both snapshots must compare equal after a
     * rows-only interaction; a new migration row or relation means a deploy happened.
     */
    record SchemaSnapshot(List<String> appliedVersionRows, List<String> relations) {

        String describe() {
            return "history rows=" + appliedVersionRows.size() + " relations=" + relations.size();
        }
    }

    static SchemaSnapshot snapshot() {
        requirePostgresDriver();
        List<String> historyRows = new ArrayList<>();
        List<String> relations = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            try (Statement statement = owner.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "select version || ' ' || script from " + SCHEMA_HISTORY + " order by installed_rank")) {
                while (rs.next()) {
                    historyRows.add(rs.getString(1));
                }
            }
        } catch (SQLException historyTableAbsent) {
            // No Flyway run has ever happened here - the earlier gates name that; the snapshot marks it.
            historyRows.add("history-table-absent");
        }
        try (Connection owner = ownerConnection()) {
            try (Statement statement = owner.createStatement();
                    ResultSet rs = statement.executeQuery("select table_name from information_schema.tables "
                            + "where table_schema = 'public' order by table_name")) {
                while (rs.next()) {
                    relations.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the relation read failed", e);
        }
        return new SchemaSnapshot(List.copyOf(historyRows), List.copyOf(relations));
    }

    // ------------------------------------------------------------------ directory seeding (the E1 denial-suite shape)

    /** The directory rows the platform's identity contract needs: the tenant, then its app user of the issuer. */
    record SeededTenant(UUID id, String code, UUID userId, String subject) {
    }

    static SeededTenant tenantA() {
        return SEEDED.get("alpha");
    }

    static SeededTenant tenantB() {
        return SEEDED.get("bravo");
    }

    private static SeededTenant seedTenant(Connection owner, String word) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantCode = "tnt-" + word + "-" + Long.toUnsignedString(Math.abs(RANDOM.nextLong()), 36);
        String subject = "sub-" + word + "-" + UUID.randomUUID();
        try (PreparedStatement tenant = owner.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            tenant.setString(1, tenantId.toString());
            tenant.setString(2, tenantCode);
            tenant.executeUpdate();
        }
        try (PreparedStatement user = owner.prepareStatement(
                "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
            user.setString(1, userId.toString());
            user.setString(2, tenantId.toString());
            user.setString(3, OIDC_ISSUER.toString());
            user.setString(4, subject);
            user.executeUpdate();
        }
        // E4 T-099 seeding: roles are role_grant rows; the seeded author drives every config operation (tenant admin)
        try (PreparedStatement grant = owner.prepareStatement(
                "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')")) {
            grant.setString(1, tenantId.toString());
            grant.setString(2, userId.toString());
            grant.executeUpdate();
        }
        return new SeededTenant(tenantId, tenantCode, userId, subject);
    }

    // ------------------------------------------------------------------ misc

    /** A code the contract suites generate per call (per-tenant uniqueness; never a file - R07). */
    static String randomCode(String prefix) {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(prefix).append('-');
        for (byte b : bytes) {
            out.append(Character.forDigit((b & 0x0f) % 36, 36));
        }
        return out.toString();
    }

}