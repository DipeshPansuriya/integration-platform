package io.integrationplatform.routing.adapter.kafkatopic;

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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * E15/E9 gap (T71 for Kafka): a broker that never acknowledges must not pin the shared database pool. The sender
 * stub is the hanging broker; the counting transaction manager stands for the pool. The outbound's reads run in
 * their own short transaction and none is open while the sender waits.
 */
class KafkaPoolIsolationTest {

    @Test
    void noTransactionIsOpenWhileAHangingBrokerHoldsTheCall() {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger openDuringReads = new AtomicInteger(-1);
        AtomicInteger openWhileSending = new AtomicInteger(-1);
        KafkaOutbound reads = send -> {
            openDuringReads.set(pool.open());
            return Optional.of(new KafkaMessage(KafkaTopicSettings.fromJson(
                    "{\"bootstrapServers\":\"localhost:9092\",\"topic\":\"orders-out\"}"), new byte[] {'x'}));
        };
        KafkaSender hanging = (settings, deliveryId, body, timeout) -> {
            openWhileSending.set(pool.open()); // the broker holds the call; it never acknowledges
            throw new TimeoutException("no ack");
        };
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("localhost")),
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        KafkaTopicTargetCall call = new KafkaTopicTargetCall(
                KafkaAdapterConfiguration.inShortTransaction(reads, pool), hanging, guard, Set.of("platform-work"),
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.NOOP,
                Duration.ofSeconds(1));
        Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);

        assertThatThrownBy(() -> call.call(send)).isInstanceOf(TargetFaultException.class);

        assertThat(pool.begun()).as("the reads ran in a short transaction of their own").isEqualTo(1);
        assertThat(openDuringReads.get()).as("the reads ran inside that transaction").isEqualTo(1);
        assertThat(openWhileSending.get()).as("pooled connections held while the broker hangs").isZero();
        assertThat(pool.open()).as("nothing leaks after the call").isZero();
    }
}
