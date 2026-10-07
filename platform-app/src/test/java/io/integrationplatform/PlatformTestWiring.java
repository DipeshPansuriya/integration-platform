package io.integrationplatform;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E1 test wiring for every platform-app container test (T-002): the application is configured only through the fixed
 * environment variable names (R07), never through {@code @ServiceConnection}, so the restricted login is what the app
 * really uses.
 *
 * <ul>
 *   <li>{@code DB_MIGRATION_USERNAME} / {@code DB_MIGRATION_PASSWORD}: the container's owner user (Flyway, lane C).</li>
 *   <li>{@code DB_URL} / {@code DB_USERNAME} / {@code DB_PASSWORD}: a LOGIN user created at runtime with a random
 *       password; it is made a member of {@code ipaas_app} once Flyway has created that role.</li>
 *   <li>{@code OIDC_ISSUER_URI} / {@code OIDC_AUDIENCE}: test values (lane B); the issuer is never contacted.</li>
 *   <li>{@code CONSOLE_OIDC_*} / {@code CONSOLE_POST_LOGOUT_REDIRECT_URI} (E4, T-097): the console login client; the
 *       secret is random per run and nothing contacts the issuer until a console login starts. A suite that logs in
 *       through Keycloak overrides them (identity.ConsoleLoginContainerTest).</li>
 * </ul>
 */
public final class PlatformTestWiring {

    /** The test issuer; {@code .invalid} never resolves, so nothing can contact it. */
    public static final URI OIDC_ISSUER = URI.create("https://issuer.e1-suite.invalid/realms/platform");

    public static final String OIDC_AUDIENCE = "integration-platform-api";

    /** The restricted application role created by lane C's migration (ADR-009). */
    public static final String APPLICATION_ROLE = "ipaas_app";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PlatformTestWiring() {
    }

    /** A LOGIN user the app connects as; its password exists only in memory for this run. */
    public record RestrictedLogin(String username, String password) {

        @Override
        public String toString() {
            return "RestrictedLogin[" + username + "]";
        }
    }

    /** Registers every fixed environment name for a test context; creates the restricted login on first use. */
    public static RestrictedLogin register(DynamicPropertyRegistry registry, PostgreSQLContainer postgres) {
        postgres.start(); // idempotent; the login must exist before the context's pool first connects
        RestrictedLogin login = createRestrictedLogin(postgres);
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", login::username);
        registry.add("DB_PASSWORD", login::password);
        registry.add("DB_MIGRATION_USERNAME", postgres::getUsername);
        registry.add("DB_MIGRATION_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER_URI", OIDC_ISSUER::toString);
        registry.add("OIDC_AUDIENCE", () -> OIDC_AUDIENCE);
        String consoleSecret = randomSecret();
        registry.add("CONSOLE_OIDC_CLIENT_ID", () -> "platform-console");
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", () -> consoleSecret);
        registry.add("CONSOLE_OIDC_REDIRECT_URI", () -> "http://localhost/login/oauth2/code/keycloak");
        registry.add("CONSOLE_POST_LOGOUT_REDIRECT_URI", () -> "http://localhost/");
        return login;
    }

    /** A connection as the container's owner user (the Flyway owner): seeding and fixtures only, never the app. */
    public static Connection ownerConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Makes the restricted login a member of {@code ipaas_app}; call after Flyway has created the role. */
    public static void grantApplicationRole(PostgreSQLContainer postgres, RestrictedLogin login) throws SQLException {
        executeFormatted(postgres, "GRANT %I TO %I", APPLICATION_ROLE, login.username());
    }

    private static String randomSecret() {
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static RestrictedLogin createRestrictedLogin(PostgreSQLContainer postgres) {
        String username = "app_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        RestrictedLogin login = new RestrictedLogin(username, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        try {
            // Postgres 15+: PUBLIC has no CREATE on schema public, so this login can connect and nothing more until
            // it is granted ipaas_app. NOSUPERUSER NOBYPASSRLS are the defaults; stated so the intent is explicit.
            executeFormatted(postgres, "CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L",
                    login.username(), login.password());
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the restricted app login", e);
        }
        return login;
    }

    /** DDL cannot take bind parameters, so the statement is built server-side by format() with %I / %L quoting. */
    private static void executeFormatted(PostgreSQLContainer postgres, String template, String... args) throws SQLException {
        StringBuilder call = new StringBuilder("SELECT format(?");
        for (int i = 0; i < args.length; i++) {
            call.append(", ?");
        }
        call.append(')');
        try (Connection owner = ownerConnection(postgres)) {
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
}
