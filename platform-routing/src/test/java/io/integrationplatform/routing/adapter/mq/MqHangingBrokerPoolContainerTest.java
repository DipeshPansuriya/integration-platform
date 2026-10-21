package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.routing.stage2.RoutingTestDatabase;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.GenericContainer;

/**
 * E15/E9 gap (T71 for MQ; modelled on {@code HangingTargetIsolationContainerTest}): a queue manager that accepts the
 * connection and never answers (the Artemis container is PAUSED) must not pin the shared database pool. The pool is
 * the real PostgreSQL pool of ONE connection - the smallest pool a whole-call transaction would exhaust.
 *
 * <pre>
 * Given the broker is frozen and the pool holds a single connection
 * When an MQ send is hanging on the broker (its outbound read the database first)
 * Then no pooled connection is held, and a second borrower gets the connection at once
 * </pre>
 *
 * <p>Authored without a Docker run (the Docker lock belongs to another lane); run it with the {@code container} tag.
 */
@Tag("container")
class MqHangingBrokerPoolContainerTest {

    static final GenericContainer<?> BROKER = ArtemisContainers.artemis();

    @BeforeAll
    static void start() {
        BROKER.start();
    }

    @AfterAll
    static void stop() {
        BROKER.stop();
    }

    @Test
    void aFrozenBrokerHoldsNoPooledConnectionAndAnotherBorrowerIsServed() throws Exception {
        int port = BROKER.getMappedPort(ArtemisContainers.ACCEPTOR_PORT);
        try (HikariDataSource pool = RoutingTestDatabase.get().appPool(1)) {
            DataSourceTransactionManager transactions = new DataSourceTransactionManager(pool);
            JdbcClient jdbc = JdbcClient.create(pool);
            MqOutbound reads = send -> {
                jdbc.sql("select 1").query(Integer.class).single(); // the outbound's read, inside the short tx
                return Optional.of(new MqMessage(new MqSettings("localhost", port, "t71.mq"), ArtemisContainers.USER,
                        ArtemisContainers.PASSWORD.toCharArray(), "id\r\n1\r\n".getBytes(StandardCharsets.UTF_8)));
            };
            DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("localhost")),
                    h -> List.of(InetAddress.getByName("127.0.0.1")), List.of("localhost"), List.of(port));
            MqTargetCall call = new MqTargetCall(MqTargetCallConfiguration.inShortTransaction(reads, transactions),
                    guard, CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.NOOP,
                    Duration.ofSeconds(6));
            Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), true);

            BROKER.getDockerClient().pauseContainerCmd(BROKER.getContainerId()).exec();
            try {
                CompletableFuture<Throwable> hanging = CompletableFuture.supplyAsync(() -> {
                    try {
                        call.call(send);
                        return null;
                    } catch (RuntimeException failed) {
                        return failed;
                    }
                });
                Thread.sleep(2_000); // the send has read the database and is now waiting on the frozen broker

                assertThat(hanging).as("the send is still hanging on the broker").isNotDone();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections())
                        .as("pooled connections held while the broker hangs").isZero();
                try (Connection another = pool.getConnection()) { // would time out behind a whole-call transaction
                    assertThat(another.isValid(2)).isTrue();
                }
                assertThat(hanging.get()).as("the frozen broker ends the call as a transient fault").isNotNull();
            } finally {
                BROKER.getDockerClient().unpauseContainerCmd(BROKER.getContainerId()).exec();
            }
        }
    }
}
