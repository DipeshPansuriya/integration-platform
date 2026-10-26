package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T204 (L4): V2_4_1370 adds a NOT VALID CHECK on the partitioned {@code delivery} table, which takes ACCESS EXCLUSIVE
 * and, queued behind a long transaction, would block every delivery for as long as that transaction runs. The
 * migration file cannot change (it is on origin/main, Flyway checksums), so {@code spring.flyway.init-sqls}
 * (config/flyway-lock-timeout.yml) sets {@code lock_timeout} on the migration connection, shaped here as {@link #initSql}. Real PostgreSQL, one database per test.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationLockTimeoutContainerTest {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private PostgreSQLContainer postgres;
    private int databases;

    @BeforeAll
    void start() {
        postgres = new PostgreSQLContainer(TestImages.POSTGRES);
        postgres.start();
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("a conflicting lock on delivery makes V2_4_1370 fail fast with 55P03, and the retry applies cleanly")
    void conflictingLockFailsFastThenRetryApplies() throws Exception {
        String url = newDatabase();
        flyway(url, null).target("2.4.1360").load().migrate();

        try (Connection holder = connect(url)) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.execute("LOCK TABLE public.delivery IN ACCESS SHARE MODE"); // a long reader/writer transaction
            }
            long started = System.nanoTime();
            assertThatThrownBy(() -> flyway(url, initSql("1s")).load().migrate())
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo(LOCK_NOT_AVAILABLE));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(60));
            assertThat(scalar(url, "SELECT count(*) FROM flyway_schema_history WHERE version = '2.4.1370'"))
                    .as("the failed migration left no history row").isEqualTo("0");
            assertThat(scalar(url, "SELECT count(*) FROM pg_constraint WHERE conname = 'delivery_contract_pin_check'"))
                    .as("and no constraint").isEqualTo("0");
        }

        // quiet window: lock released, the same command now applies the whole remaining set
        flyway(url, initSql("1s")).load().migrate();
        assertThat(scalar(url, "SELECT bool_and(convalidated)::text FROM pg_constraint "
                + "WHERE conname = 'delivery_contract_pin_check'")).isEqualTo("true");
    }

    @Test
    @DisplayName("control: without the callback the same migration waits behind the lock until it is released")
    void withoutCallbackItWaits() throws Exception {
        String url = newDatabase();
        flyway(url, null).target("2.4.1360").load().migrate();

        CompletableFuture<Void> migration;
        try (Connection holder = connect(url)) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.execute("LOCK TABLE public.delivery IN ACCESS SHARE MODE");
            }
            migration = CompletableFuture.runAsync(() -> flyway(url, null).load().migrate());
            assertThatThrownBy(() -> migration.get(5, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
        }
        migration.get(120, TimeUnit.SECONDS); // lock released: it completes
    }

    @Test
    @DisplayName("with no conflicting lock the full migration set applies and every migration ran under the timeout")
    void fullSetAppliesAndEveryMigrationSawTheTimeout() throws Exception {
        String url = newDatabase();
        flyway(url, initSql("7s"), "classpath:db/locktimeout-probe").load().migrate();

        assertThat(scalar(url, "SELECT seen FROM public.lock_timeout_probe")).isEqualTo("7s");
        assertThat(scalar(url, "SELECT bool_and(convalidated)::text FROM pg_constraint "
                + "WHERE conname = 'delivery_contract_pin_check'")).isEqualTo("true");
        assertThat(scalar(url, "SELECT count(*) FROM flyway_schema_history WHERE NOT success")).isEqualTo("0");
        assertThat(scalar(url, "SHOW lock_timeout")).as("session-scoped to the migration connection: nothing leaks to other sessions")
                .isEqualTo("0");
    }

    @Test
    @DisplayName("V2_4_1400 (VALIDATE) is not disturbed by a concurrent writer: SHARE UPDATE EXCLUSIVE does not conflict")
    void validateRunsBesideAWriter() throws Exception {
        String url = newDatabase();
        flyway(url, null).target("2.4.1390").load().migrate();

        try (Connection writer = connect(url)) {
            writer.setAutoCommit(false);
            try (Statement s = writer.createStatement()) {
                s.execute("LOCK TABLE public.delivery IN ROW EXCLUSIVE MODE"); // what an INSERT/UPDATE holds
            }
            flyway(url, initSql("1s")).load().migrate();
        }
        assertThat(scalar(url, "SELECT bool_and(convalidated)::text FROM pg_constraint "
                + "WHERE conname = 'delivery_contract_pin_check'")).isEqualTo("true");
    }

    /** The same statement config/flyway-lock-timeout.yml gives Flyway as spring.flyway.init-sql. */
    private static String initSql(String timeout) {
        return "SET lock_timeout = '" + timeout + "'";
    }

    private FluentConfiguration flyway(String url, String initSql, String... extraLocations) {
        String[] locations = new String[1 + extraLocations.length];
        locations[0] = "classpath:db/migration";
        System.arraycopy(extraLocations, 0, locations, 1, extraLocations.length);
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword()).locations(locations);
        return initSql == null ? configuration : configuration.initSql(initSql);
    }

    private String newDatabase() throws SQLException {
        String name = "lt_" + (++databases);
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
    }

    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private String scalar(String url, String sql) throws SQLException {
        try (Connection c = connect(url); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
