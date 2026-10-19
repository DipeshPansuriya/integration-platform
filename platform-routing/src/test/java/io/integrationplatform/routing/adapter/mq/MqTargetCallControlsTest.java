package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2AttemptWriter;
import io.integrationplatform.routing.stage2.Stage2OutcomeHandoff;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

/**
 * T98 (ADR-018) resilient-integration conformance for the MQ send, no Docker: a closed port is a broker that is
 * down, a socket that accepts and never answers is a silent broker.
 */
class MqTargetCallControlsTest {

    static final String LOOPBACK = "127.0.0.1";

    // checkHost refuses IP literals and the guard refuses an IP showcase host, so the guard sees a name
    static final String HOST = "localhost";

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ObservationRegistry observations = ObservationRegistry.create();
    final List<String> stopped = new CopyOnWriteArrayList<>();

    MqTargetCallControlsTest() {
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context.getName() + " outcome=" + context.getLowCardinalityKeyValue("outcome").getValue());
            }
        });
    }

    static DestinationGuard loopbackGuard(int port) {
        return new DestinationGuard(AllowList.of(List.of(HOST)), host -> List.of(InetAddress.getByName(LOOPBACK)),
                List.of(HOST), List.of(port));
    }

    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            return socket.getLocalPort();
        }
    }

    static MqOutbound outbound(int port, AtomicInteger resolves) {
        return send -> {
            resolves.incrementAndGet();
            return Optional.of(new MqMessage(new MqSettings(HOST, port, "t98.csv"), "t98", "pw".toCharArray(),
                    "id,qty\n1,2\n".getBytes(StandardCharsets.UTF_8)));
        };
    }

    static Stage2Send send(UUID endpoint) {
        return new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "order-1", endpoint, false);
    }

    MqTargetCall call(int port, AtomicInteger resolves, CircuitBreakerRegistry breakers) {
        return new MqTargetCall(outbound(port, resolves), loopbackGuard(port), breakers, meters, observations,
                Duration.ofSeconds(5));
    }

    @Test
    void brokerDownIsTransientRedeliveredTheConfiguredCountThenHandedToRetryWait() throws Exception {
        int port = closedPort();
        List<OutcomeClass> attempts = new CopyOnWriteArrayList<>();
        List<OutcomeClass> handedOff = new CopyOnWriteArrayList<>();
        AtomicInteger attemptsSoFar = new AtomicInteger();
        Stage2AttemptWriter writer = (id, layer, outcome, started, finished, detail) -> attempts.add(outcome);
        Stage2OutcomeHandoff handoff = (id, token, soFar, outcome, idempotent) -> {
            handedOff.add(outcome);
            attemptsSoFar.set(soFar);
        };
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new LayerOneRouteBuilder(call(port, new AtomicInteger(), CircuitBreakerRegistry.ofDefaults()),
                    writer, handoff, new LayerOneWaits(2, Duration.ofMillis(10), Duration.ofMillis(20))));
            context.start();
            context.createProducerTemplate().sendBody("direct:stage2-send", send(UUID.randomUUID()));
        }

        assertThat(attempts).as("1 attempt + 2 redeliveries, all transient").containsExactly(
                OutcomeClass.TRANSIENT, OutcomeClass.TRANSIENT, OutcomeClass.TRANSIENT);
        assertThat(handedOff).as("exhausted -> RETRY_WAIT hand-off, 0 rows lost").containsExactly(OutcomeClass.TRANSIENT);
        assertThat(attemptsSoFar).hasValue(3);
    }

    @Test
    void silentBrokerFailsTransientWithinSixSeconds() throws Exception {
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getByName(LOOPBACK))) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        held.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // the test is over
                }
            });
            MqTargetCall call = call(silent.getLocalPort(), new AtomicInteger(), CircuitBreakerRegistry.ofDefaults());
            long start = System.nanoTime();

            assertThatThrownBy(() -> call.call(send(UUID.randomUUID())))
                    .isInstanceOf(TargetFaultException.TransientFaultException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThanOrEqualTo(Duration.ofSeconds(6));
            acceptor.interrupt();
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    void fiveBrokerDownSendsOpenTheBreakerAndTheSixthIsNotAttempted() throws Exception {
        int port = closedPort();
        AtomicInteger resolves = new AtomicInteger();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        UUID endpoint = UUID.randomUUID();
        MqTargetCall call = call(port, resolves, breakers);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> call.call(send(endpoint))).isInstanceOf(TargetFaultException.TransientFaultException.class);
        }
        assertThat(breakers.circuitBreaker("mq:" + endpoint).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> call.call(send(endpoint))).isInstanceOf(TargetFaultException.TransientFaultException.class);

        assertThat(resolves).as("the 6th send is not attempted").hasValue(5);
        assertThat(meters.get("mq.target.send").tag("target_type", "MQ").tag("outcome", "TRANSIENT").timer().count())
                .isEqualTo(5);
    }

    @Test
    void everyAttemptIsTimedCountedAndObservedPerTargetType() throws Exception {
        int port = closedPort();
        MqTargetCall call = call(port, new AtomicInteger(), CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> call.call(send(UUID.randomUUID()))).isInstanceOf(TargetFaultException.class);

        assertThat(meters.get("mq.target.outcome").tag("target_type", "MQ").tag("outcome", "TRANSIENT").counter().count())
                .isEqualTo(1.0);
        assertThat(stopped).containsExactly("mq.target.send outcome=TRANSIENT");
    }
}
