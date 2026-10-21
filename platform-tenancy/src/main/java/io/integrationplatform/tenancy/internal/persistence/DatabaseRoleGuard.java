package io.integrationplatform.tenancy.internal.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * K1 (T146, Dipesh m-263aac): the application's database login must not be able to bypass row-level security -
 * not a superuser, not BYPASSRLS, and not the owner of any tenant table. Otherwise every tenant-isolation control
 * collapses silently, so the application refuses to start in every environment instead of running unprotected.
 *
 * <p>The check runs once, against the role the application pool actually connects as. A missing {@link DataSource}
 * (a slice test with no database) skips it and fails closed at the query layer instead.
 */
@Component
@ConditionalOnBean(DataSource.class)
class DatabaseRoleGuard {

    private final DataSource dataSource;

    DatabaseRoleGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @jakarta.annotation.PostConstruct
    void verifyLoginRole() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            try (ResultSet attributes = statement.executeQuery(
                    "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
                if (!attributes.next()) {
                    throw new IllegalStateException("application database login has no pg_roles row");
                }
                if (attributes.getBoolean("rolsuper")) {
                    throw new IllegalStateException("application database login must not be a superuser");
                }
                if (attributes.getBoolean("rolbypassrls")) {
                    throw new IllegalStateException("application database login must not bypass row-level security");
                }
            }
            try (ResultSet ownership = statement.executeQuery(
                    "SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner"
                            + " WHERE r.rolname = current_user"
                            + " AND c.relnamespace = 'public'::regnamespace"
                            + " AND c.relkind IN ('r', 'p')")) {
                ownership.next();
                if (ownership.getLong(1) > 0) {
                    throw new IllegalStateException(
                            "application database login must not own a tenant table (run migrations as the owner role)");
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not verify the application database login's role", e);
        }
    }
}
