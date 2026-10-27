package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * K1 (T146, project-owner decision m-263aac): the application's database login must not be able to bypass row-level security -
 * not a superuser, not BYPASSRLS, and not the owner (or a member of an owner role) of any table in any application
 * schema. Otherwise every tenant-isolation control collapses silently, so the application refuses to start in every
 * environment instead of running unprotected.
 *
 * <p>Wiring (row 393, Karan m-aec16c): the guard used to be {@code @ConditionalOnBean(DataSource.class)} on a scanned
 * component. Component scanning is evaluated before the DataSource auto-configuration registers its bean, so the
 * condition was false and the guard never existed. It is now unconditional and looks the DataSource up lazily, and
 * the check runs in {@link #afterSingletonsInstantiated()} - after every singleton, Flyway's migration initializer
 * included - so the ownership check sees the migrated tables and a refusal fails the startup with a clear message.
 * No DataSource at all (a slice test with no database) skips the check and fails closed at the query layer instead.
 *
 * <p>The refusal wording is part of the contract: the hosting smoke test greps for "must not own a tenant table".
 */
@Component
@Lazy(false) // spring.main.lazy-initialization=true must not be able to skip the guard
@TenantBoundaryCrossing("T6")
class DatabaseRoleGuard implements SmartInitializingSingleton {

    /** Tables (plain and partitioned) in every non-system schema that the login is, or can act as, the owner of. */
    private static final String OWNED_TABLES = "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE c.relkind IN ('r', 'p')"
            + " AND left(n.nspname, 3) <> 'pg_' AND n.nspname <> 'information_schema'"
            + " AND pg_has_role(current_user, c.relowner, 'MEMBER')";

    private final ObjectProvider<DataSource> dataSource;

    @Autowired
    DatabaseRoleGuard(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    /** Test seam: a guard for one known DataSource. */
    DatabaseRoleGuard(DataSource dataSource) {
        this.dataSource = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                java.util.Map.of("dataSource", dataSource)).getBeanProvider(DataSource.class);
    }

    @Override
    public void afterSingletonsInstantiated() {
        verifyLoginRole();
    }

    void verifyLoginRole() {
        DataSource source = dataSource.getIfUnique();
        if (source == null) {
            if (dataSource.stream().findAny().isPresent()) {
                // present but not unique: silently checking nothing would be the original K1 defect again
                throw new IllegalStateException(
                        "K1: more than one DataSource and none is primary - cannot tell which login the application uses");
            }
            return;
        }
        try (Connection connection = source.getConnection();
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
            try (ResultSet ownership = statement.executeQuery(OWNED_TABLES)) {
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
