package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.routing.poolisolation.CountingTransactionManager;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * E15/E9 gap (T71 for MQ): a hanging queue manager must not pin the shared database pool. The target is a TCP stub
 * that accepts and never answers; the counting transaction manager stands for the pool. The outbound's reads run in
 * their own short transaction, and NO transaction is open while the call waits on the broker.
 */
class MqPoolIsolationTest {

    @Test
    void noTransactionIsOpenWhileAHangingBrokerHoldsTheCall() throws Exception {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger openDuringReads = new AtomicInteger(-1);
        try (ServerSocket stub = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            int port = stub.getLocalPort();
            MqOutbound reads = send -> {
                openDuringReads.set(pool.open());
                return Optional.of(new MqMessage(new MqSettings("localhost", port, "q"), "u", "p".toCharArray(),
                        new byte[] {'x'}));
            };
            AtomicInteger openWhileHung = new AtomicInteger(-1);
            CountDownLatch hung = new CountDownLatch(1);
            Thread accepting = Thread.ofPlatform().start(() -> {
                try (Socket held = stub.accept()) {
                    openWhileHung.set(pool.open()); // the broker has the connection and will never answer
                    hung.countDown();
                    Thread.sleep(2_000);
                } catch (Exception ignored) {
                    hung.countDown();
                }
            });
            DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("localhost")),
                    host -> List.of(InetAddress.getByName("127.0.0.1")), List.of("localhost"), List.of(port));
            MqTargetCall call = new MqTargetCall(MqTargetCallConfiguration.inShortTransaction(reads, pool), guard,
                    CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.NOOP,
                    Duration.ofSeconds(1));
            Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);

            assertThatThrownBy(() -> call.call(send)).isInstanceOf(TargetFaultException.class);

            assertThat(hung.await(5, TimeUnit.SECONDS)).as("the stub saw the call").isTrue();
            accepting.join();
            assertThat(pool.begun()).as("the reads ran in a short transaction of their own").isEqualTo(1);
            assertThat(openDuringReads.get()).as("the reads ran inside that transaction").isEqualTo(1);
            assertThat(openWhileHung.get()).as("pooled connections held while the broker hangs").isZero();
            assertThat(pool.open()).as("nothing leaks after the call").isZero();
        }
    }
}
