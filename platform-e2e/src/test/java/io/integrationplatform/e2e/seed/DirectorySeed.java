package io.integrationplatform.e2e.seed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * Row 327 T157: the tenant directory rows of a seeded tenant, written the way the platform's own container fixtures
 * write them (platform-app ConsoleLoginContainerTest): one {@code public.tenant} row, then per realm user one
 * {@code public.app_user} row keyed by the token's {@code (iss, sub)} and one {@code public.role_grant} row. Runs as the
 * schema owner (RLS is not forced, so the owner writes across tenants - never the restricted {@code ipaas_app} role),
 * parameterized, in one transaction: a failure leaves no half tenant behind.
 */
public final class DirectorySeed {

    private DirectorySeed() {
    }

    /** Writes the tenant and its users; returns the tenant's id. {@code roleBySubject}: token subject to role. */
    public static UUID apply(Connection owner, String tenantCode, String issuer, Map<String, String> roleBySubject)
            throws SQLException {
        boolean autoCommit = owner.getAutoCommit();
        owner.setAutoCommit(false);
        try {
            UUID tenant = returningId(owner, "INSERT INTO public.tenant (code, status) VALUES (?, 'ACTIVE') RETURNING id",
                    tenantCode);
            for (Map.Entry<String, String> user : roleBySubject.entrySet()) {
                UUID userId = returningId(owner, "INSERT INTO public.app_user (tenant_id, issuer, subject, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE') RETURNING id", tenant, issuer, user.getKey());
                try (PreparedStatement grant = owner.prepareStatement(
                        "INSERT INTO public.role_grant (tenant_id, user_id, role) VALUES (?, ?, ?)")) {
                    grant.setObject(1, tenant);
                    grant.setObject(2, userId);
                    grant.setString(3, user.getValue());
                    grant.executeUpdate();
                }
            }
            owner.commit();
            return tenant;
        } catch (SQLException | RuntimeException failure) {
            owner.rollback();
            throw failure;
        } finally {
            owner.setAutoCommit(autoCommit);
        }
    }

    private static UUID returningId(Connection owner, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }
}
