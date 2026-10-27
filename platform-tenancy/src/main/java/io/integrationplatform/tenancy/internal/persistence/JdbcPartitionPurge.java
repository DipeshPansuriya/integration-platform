package io.integrationplatform.tenancy.internal.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.PartitionPurge;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E14 T-142 / T-189: {@link PartitionPurge} over the maintenance connection (a login that is a member of
 * {@code ipaas_maintenance}, control T6: partition DDL is never the application role's). It calls the one definer
 * function {@code public.payload_purge_days(cutoff, dry_run)} (V2_4_1450), which holds the retention floor, the
 * dead-letter guard and the drop; this class reads nothing else and no tenant.
 *
 * <p>The pool is private: it is deliberately not a {@code DataSource} bean, because a second one would make Spring
 * Boot back off the application's own data source and could be injected into tenant code by accident.
 */
@TenantBoundaryCrossing("T6")
public final class JdbcPartitionPurge implements PartitionPurge, AutoCloseable {

    private static final String SQL = "select purge_day, outcome from public.payload_purge_days(?, ?)";

    private final JdbcTemplate jdbc;
    private final HikariDataSource ownedPool;

    /** Over a caller-owned data source (tests); {@link #close()} then does nothing. */
    public JdbcPartitionPurge(DataSource maintenance) {
        this(new JdbcTemplate(Objects.requireNonNull(maintenance, "maintenance")), null);
    }

    private JdbcPartitionPurge(JdbcTemplate jdbc, HikariDataSource ownedPool) {
        this.jdbc = jdbc;
        this.ownedPool = ownedPool;
    }

    /** A purge over its own small pool, closed by {@link #close()}. */
    public static JdbcPartitionPurge pooled(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ipaas-maintenance");
        config.setJdbcUrl(Objects.requireNonNull(url, "url"));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        // the pool must not fail the application's start when the database is briefly away; the run fails instead
        config.setInitializationFailTimeout(-1);
        HikariDataSource pool = new HikariDataSource(config);
        return new JdbcPartitionPurge(new JdbcTemplate(pool), pool);
    }

    @Override
    public Outcome purgeBefore(LocalDate cutoff, boolean dryRun) {
        List<LocalDate> dropped = new ArrayList<>();
        List<LocalDate> kept = new ArrayList<>();
        jdbc.query(SQL, rs -> {
            LocalDate day = rs.getObject(1, LocalDate.class);
            if ("KEPT".equals(rs.getString(2))) {
                kept.add(day);
            } else {
                dropped.add(day);
            }
        }, cutoff, dryRun);
        return new Outcome(dryRun, List.copyOf(dropped), List.copyOf(kept));
    }

    @Override
    public void close() {
        if (ownedPool != null) {
            ownedPool.close();
        }
    }
}
