package io.integrationplatform.routing.store;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test-only (R05/R07 clean): a restricted LOGIN that is a member of {@code ipaas_app}, the same shape as
 * platform-ingest's {@code AppRoleHarness} (that class lives in ingest's test sources and is not on this module's
 * test classpath - routing's container tests need their own copy). The password exists only in memory for this
 * run; no secret ever enters a file (R07).
 *
 * <p>The container's owner user is a superuser and bypasses row-level security, so only a connection through this
 * login shows what the application role really can persist - which is exactly what the intake transaction does in
 * production.
 */
public final class IntakeStoreAppRole {

    /** The restricted application role the migrations grant to (V1_3_1 and every family grant after it). */
    public static final String APPLICATION_ROLE = "ipaas_app";

    private static final SecureRandom RANDOM = new SecureRandom();

    private IntakeStoreAppRole() {
    }

    /** A LOGIN user; its random password never leaves memory. */
    public record Login(String username, String password) {

        @Override
        public String toString() {
            return "Login[" + username + "]";
        }
    }

    /** Creates the restricted login and grants it {@code ipaas_app}; call after Flyway has created the role. */
    public static Login grantAppRoleLogin(PostgreSQLContainer postgres) {
        String username = "route_app_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        try {
            // Both strings are from the base64url alphabet (no quotes to escape) and the username is generated
            // alphanumeric, so the DDL is safe to build directly.
            execute(postgres, "CREATE ROLE " + username
                    + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '" + password + "'");
            execute(postgres, "GRANT " + APPLICATION_ROLE + " TO " + username);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not create the restricted app login", failure);
        }
        return new Login(username, password);
    }

    /** A connection as the app login (not the owner): RLS and grants apply to every statement on it. */
    public static Connection connectAsAppRole(PostgreSQLContainer postgres, Login login) throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login.username(), login.password());
        connection.setAutoCommit(true);
        return connection;
    }

    private static void execute(PostgreSQLContainer postgres, String ddl) throws SQLException {
        try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = owner.createStatement()) {
            statement.execute(ddl);
        }
    }
}