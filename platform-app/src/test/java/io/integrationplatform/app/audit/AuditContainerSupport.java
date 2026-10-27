package io.integrationplatform.app.audit;

import io.integrationplatform.PlatformTestWiring;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 544 test plumbing: raw JDBC sessions as the restricted application login (member of {@code ipaas_app}) with the
 * tenant stamped the way the app stamps it, so a test can prove what that role may and may not do, and the owner
 * session for fixtures only.
 */
final class AuditContainerSupport {

    static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-0000000000b1");

    private AuditContainerSupport() {
    }

    /** Seeds two tenants and the login's role membership; call once after the context (and Flyway) is up. */
    static void seed(PostgreSQLContainer postgres, PlatformTestWiring.RestrictedLogin login) throws SQLException {
        PlatformTestWiring.grantApplicationRole(postgres, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 'row544-a', 'ACTIVE', 'Etc/UTC'),"
                    + " ('" + TENANT_B + "', 'row544-b', 'ACTIVE', 'Etc/UTC')"
                    + " on conflict (id) do nothing");
        }
    }

    /** A session as the application login; {@code tenant} may be null (nothing stamped). Autocommit on. */
    static Connection appSession(PostgreSQLContainer postgres, PlatformTestWiring.RestrictedLogin login, UUID tenant)
            throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login.username(), login.password());
        if (tenant != null) {
            try (PreparedStatement stamp = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                stamp.setString(1, tenant.toString());
                stamp.execute();
            }
        }
        return connection;
    }

    /** Appends one pending outbox row as the app login under {@code tenant}; returns its id. */
    static long appendOutbox(Connection session, UUID tenant, String actor, String action, String traceId)
            throws SQLException {
        try (PreparedStatement insert = session.prepareStatement(
                "insert into public.audit_outbox (tenant_id, actor, action, target_ids, before_state, after_state,"
                        + " event_at, trace_id) values (?::uuid, ?, ?, '[\"x:1\",\"y:2\"]'::jsonb, '{\"v\":1}', null,"
                        + " '2026-01-02T03:04:05Z', ?) returning id")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, actor);
            insert.setString(3, action);
            insert.setString(4, traceId);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static int callMove(Connection session, long outboxId) throws SQLException {
        try (PreparedStatement call = session.prepareStatement("select public.audit_relay_move(?)")) {
            call.setLong(1, outboxId);
            try (ResultSet rs = call.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static long count(Connection session, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = session.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
