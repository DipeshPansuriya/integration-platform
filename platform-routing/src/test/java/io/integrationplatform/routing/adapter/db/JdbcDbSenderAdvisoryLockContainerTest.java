package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T178b against a real PostgreSQL: a table with NO UNIQUE (delivery_id), two connections and ONE delivery id give
 * exactly one stored delivery. Deterministic: connection A holds the delivery's advisory lock (the same key the sender
 * takes) and inserts after B has started; B must wait for A's commit, then see the row and skip.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDbSenderAdvisoryLockContainerTest {

    static final PostgreSQLContainer TARGET = DbTargetContainers.target();

    @BeforeAll
    void start() throws Exception {
        TARGET.start();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("create table orders_in (awb text, delivery_id uuid)");
        }
    }

    @AfterAll
    void stop() {
        TARGET.stop();
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
    }

    @Test
    @DisplayName("Two concurrent inserts of one delivery id store exactly one row (the second waits for the lock, then skips)")
    void twoConcurrentInsertsOfOneDeliveryStoreOneRow() throws Exception {
        UUID delivery = UUID.randomUUID();
        DbMessage message = new DbMessage(new DbSettings("h", 5432, "test", "u", "orders_in"), "u",
                "pw".toCharArray(), "orders_in", List.of("awb"), List.of(List.of("A1")));
        JdbcDbSender sender = new JdbcDbSender(Duration.ofSeconds(5));

        try (Connection first = connect(); Connection second = connect()) {
            first.setAutoCommit(false);
            try (var lock = first.prepareStatement(JdbcDbSender.ADVISORY_LOCK_SQL)) { // A holds the delivery's lock
                lock.setString(1, delivery.toString());
                lock.execute();
            }
            CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
                try {
                    sender.insert(second, message, delivery);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertThat(waiting).as("B must wait on A's lock").isNotDone();
            try (Statement s = first.createStatement()) { // A does its check-then-insert and commits
                s.execute("insert into orders_in values ('A1', '" + delivery + "')");
            }
            first.commit();
            waiting.get(5, TimeUnit.SECONDS);
        }
        try (Connection c = connect(); Statement s = c.createStatement();
                var rows = s.executeQuery("select count(*) from orders_in where delivery_id = '" + delivery + "'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("T189-3: a partner database that defaults to REPEATABLE READ still sees the first attempt's row after the lock wait")
    void aRepeatableReadDefaultStillStoresOneRow() throws Exception {
        UUID delivery = UUID.randomUUID();
        DbMessage message = new DbMessage(new DbSettings("h", 5432, "test", "u", "orders_in"), "u",
                "pw".toCharArray(), "orders_in", List.of("awb"), List.of(List.of("RR")));
        JdbcDbSender sender = new JdbcDbSender(Duration.ofSeconds(5));

        try (Connection first = connect(); Connection second = connect()) {
            try (Statement s = second.createStatement()) { // the partner default: a snapshot taken by the first statement
                s.execute("set session characteristics as transaction isolation level repeatable read");
            }
            first.setAutoCommit(false);
            try (var lock = first.prepareStatement(JdbcDbSender.ADVISORY_LOCK_SQL)) {
                lock.setString(1, delivery.toString());
                lock.execute();
            }
            CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
                try {
                    sender.insert(second, message, delivery);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertThat(waiting).as("B must wait on A's lock").isNotDone();
            try (Statement s = first.createStatement()) {
                s.execute("insert into orders_in values ('RR', '" + delivery + "')");
            }
            first.commit();
            waiting.get(5, TimeUnit.SECONDS);
        }
        try (Connection c = connect(); Statement s = c.createStatement();
                var rows = s.executeQuery("select count(*) from orders_in where delivery_id = '" + delivery + "'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("T189-3: the lock wait is bounded by the configured timeout (lock_not_available 55P03), not a fixed 4 s")
    void theLockWaitFollowsTheConfiguredTimeout() throws Exception {
        UUID delivery = UUID.randomUUID();
        DbMessage message = new DbMessage(new DbSettings("h", 5432, "test", "u", "orders_in"), "u",
                "pw".toCharArray(), "orders_in", List.of("awb"), List.of(List.of("LT")));
        JdbcDbSender sender = new JdbcDbSender(Duration.ofSeconds(1));

        try (Connection first = connect(); Connection second = connect()) {
            first.setAutoCommit(false);
            try (var lock = first.prepareStatement(JdbcDbSender.ADVISORY_LOCK_SQL)) {
                lock.setString(1, delivery.toString());
                lock.execute();
            }
            long start = System.nanoTime();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sender.insert(second, message, delivery))
                    .isInstanceOfSatisfying(java.sql.SQLException.class,
                            e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
            first.rollback();
        }
    }
}
