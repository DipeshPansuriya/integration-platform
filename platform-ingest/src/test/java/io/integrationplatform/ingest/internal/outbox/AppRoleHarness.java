package io.integrationplatform.ingest.internal.outbox;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test-only (R05/R07 clean): a restricted LOGIN that is a member of {@code ipaas_app}, built the same way as
 * platform-app's PlatformTestWiring (that class lives in platform-app's test sources and is not on this module's
 * classpath). The password exists only in memory for this run; no secret ever enters a file (R07).
 *
 * <p>Used by the RLS proofs: the container's owner user is a superuser and bypasses row-level security, so only a
 * connection through this login shows what the application role really can see and do.
 */
final class AppRoleHarness {

    /** The restricted application role created by V1_3_1. */
    static final String APPLICATION_ROLE = "ipaas_app";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AppRoleHarness() {
    }

    /** A LOGIN user; its random password never leaves memory. */
    record Login(String username, String password) {

        @Override
        public String toString() {
            return "Login[" + username + "]";
        }
    }

    /** Creates the restricted login and grants it {@code ipaas_app}; call after Flyway has created the role. */
    static Login grantAppRoleLogin(PostgreSQLContainer postgres) {
        String username = "ingest_app_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        try {
            // Postgres 15+: PUBLIC has no CREATE on schema public, so this login can connect and nothing more
            // until it is granted ipaas_app. NOSUPERUSER NOBYPASSRLS stated so the intent is explicit.
            executeFormatted(postgres,
                    "CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L",
                    username, password);
            executeFormatted(postgres, "GRANT %I TO %I", APPLICATION_ROLE, username);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not create the restricted app login", failure);
        }
        return new Login(username, password);
    }

    /** A connection as the app login (not the owner): RLS and grants apply to every statement on it. */
    static Connection connectAsAppRole(PostgreSQLContainer postgres, Login login) throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login.username(), login.password());
        connection.setAutoCommit(true);
        return connection;
    }

    /** A connection as the container's owner user (the Flyway owner): seeding and fixtures only, never the app. */
    static Connection ownerConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Binds app.tenant_id on the connection (session scope: this connection is one proof, then it closes). */
    static void bindTenantSetting(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement stamp = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            stamp.setString(1, tenantId);
            stamp.execute();
        }
    }

    /** DDL cannot take bind parameters, so the statement is built server-side by format() with %I / %L quoting. */
    private static void executeFormatted(PostgreSQLContainer postgres, String template, String... args)
            throws SQLException {
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
