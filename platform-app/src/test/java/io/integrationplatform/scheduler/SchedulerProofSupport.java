package io.integrationplatform.scheduler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.nio.file.Path;
import io.integrationplatform.PlatformTestWiring;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Small helpers shared by the three E14 scheduler proof suites (T-057, T-058): the fixed wiring names through
 * {@link PlatformTestWiring}, a poll loop instead of a new assertion dependency, and the owner-connection SQL the
 * suites need directly.
 */
public final class SchedulerProofSupport {

    /** The scheduler task table, in its own schema (V2_2_1400); db-scheduler takes the name qualified. */
    public static final String SCHEDULED_TASKS = "scheduler.scheduled_tasks";

    /** db-scheduler polling interval the suites run with (the "on time" tolerance is relative to this). */
    public static final String POLLING_INTERVAL = "1s";

    private SchedulerProofSupport() {
    }

    /**
     * The fixed R07 wiring of {@link PlatformTestWiring} as a plain property map for suites that build their
     * application contexts by hand; suppliers resolve immediately because the container is already running.
     */
    public static Map<String, Object> wiringProperties(PostgreSQLContainer postgres) {
        Map<String, Object> properties = new LinkedHashMap<>();
        DynamicPropertyRegistry registry = (name, supplier) -> properties.put(name, supplier.get());
        PlatformTestWiring.register(registry, postgres);
        return properties;
    }

    /**
     * The migration window every suite runs before its first instance: the real Flyway as the container's owner
     * user over every module's migration directory, exactly the way SchedulerIncidentAlertsContainerTest deploys,
     * so ipaas_app and the scheduler schema (V2_2_1400) exist before the restricted login is granted. It is not a
     * Spring context: platform-app's beans require the SchedulerClient the db-scheduler starter registers, so a
     * context with the scheduler disabled cannot start at all (SchedulerWiringOrderBridge documents the cascade) -
     * the suites' "all instances down" window is simply no context running.
     */
    public static void migrateAsOwner(PostgreSQLContainer postgres) {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String[] locations = Stream.of("platform-tenancy", "platform-config", "platform-delivery",
                        "platform-ingest", "platform-routing", "platform-app")
                .map(module -> basedir.getParent().resolve(module + "/src/main/resources/db/migration")
                        .normalize().toString().replace('\\', '/'))
                .map(path -> "filesystem:" + path)
                .toArray(String[]::new);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                .load()
                .migrate();
    }

    /** Polls until the condition holds or the timeout passes (no new dependency; db-scheduler polls every 1s here). */
    public static void pollUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }

    public static Connection ownerConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static long countRows(PostgreSQLContainer postgres, String sql, Object... parameters) throws SQLException {
        try (Connection connection = ownerConnection(postgres);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public static String singleText(PostgreSQLContainer postgres, String sql, Object... parameters)
            throws SQLException {
        try (Connection connection = ownerConnection(postgres);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                assertThatRowExists(rs);
                return rs.getString(1);
            }
        }
    }

    private static void assertThatRowExists(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            throw new AssertionError("the query that must return exactly one row returned none");
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    /** Seeds one scheduler.job_definition row for a proof job type (FIXED_DELAY shape, as the runtime reads it). */
    public static void seedJobDefinition(PostgreSQLContainer postgres, String jobType, int lateThresholdSeconds)
            throws SQLException {
        try (Connection connection = ownerConnection(postgres);
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO scheduler.job_definition (job_type, schedule_kind, schedule, enabled,"
                                + " late_threshold_seconds) VALUES (?, 'FIXED_DELAY', 'PT10S', true, ?)"
                                + " ON CONFLICT (job_type) DO NOTHING")) {
            insert.setString(1, jobType);
            insert.setInt(2, lateThresholdSeconds);
            insert.executeUpdate();
        }
    }
}