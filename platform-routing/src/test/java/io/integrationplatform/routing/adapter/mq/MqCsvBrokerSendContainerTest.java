package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2AttemptWriter;
import io.integrationplatform.routing.stage2.Stage2OutcomeHandoff;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/** T98 (ADR-018) against a real Artemis broker: the CSV lands once, a bad credential is permanent. */
@Tag("container")
class MqCsvBrokerSendContainerTest {

    static final String QUEUE = "t98.csv";
    static final String CSV = "id,qty\r\n1,2\r\n";
    static final GenericContainer<?> BROKER = ArtemisContainers.artemis();

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ObservationRegistry observations = ObservationRegistry.create();
    final List<String> spans = new CopyOnWriteArrayList<>();

    MqCsvBrokerSendContainerTest() {
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                spans.add(context.getName() + " outcome=" + context.getLowCardinalityKeyValue("outcome").getValue());
            }
        });
    }

    @BeforeAll
    static void start() {
        BROKER.start();
    }

    @AfterAll
    static void stop() {
        BROKER.stop();
    }

    static int port() {
        return BROKER.getMappedPort(ArtemisContainers.ACCEPTOR_PORT);
    }

    MqTargetCall call(String password) {
        String host = "127.0.0.1";
        MqOutbound outbound = send -> Optional.of(new MqMessage(new MqSettings(host, port(), QUEUE),
                ArtemisContainers.USER, password.toCharArray(), CSV.getBytes(StandardCharsets.UTF_8)));
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of(host)),
                h -> List.of(InetAddress.getByName(h)), List.of(host), List.of(port()));
        return new MqTargetCall(outbound, guard, CircuitBreakerRegistry.ofDefaults(), meters, observations,
                Duration.ofSeconds(5));
    }

    @Test
    void theCsvLandsOnTheQueueOnceEvenWhenSentTwice() throws Exception {
        MqTargetCall call = call(ArtemisContainers.PASSWORD);
        Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "order-1", UUID.randomUUID(), true);

        call.call(send);
        call.call(send);

        List<String> received = new ArrayList<>();
        try (Connection connection = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port())
                .createConnection(ArtemisContainers.USER, ArtemisContainers.PASSWORD)) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(QUEUE));
            long wait = 5000;
            Message message;
            while ((message = consumer.receive(wait)) != null) {
                BytesMessage bytes = (BytesMessage) message;
                byte[] body = new byte[(int) bytes.getBodyLength()];
                bytes.readBytes(body);
                received.add(new String(body, StandardCharsets.UTF_8));
                wait = 1500;
            }
        }

        assertThat(received).as("1 sent, 1 received, 0 duplicates").containsExactly(CSV);
        assertThat(meters.get("mq.target.outcome").tag("target_type", "MQ").tag("outcome", "SUCCESS").counter().count())
                .isEqualTo(2.0);
        assertThat(spans).containsExactly("mq.target.send outcome=SUCCESS", "mq.target.send outcome=SUCCESS");
    }

    @Test
    void aWrongPasswordIsPermanentOneAttemptNoRedelivery() throws Exception {
        List<OutcomeClass> attempts = new CopyOnWriteArrayList<>();
        List<OutcomeClass> handedOff = new CopyOnWriteArrayList<>();
        Stage2AttemptWriter writer = (id, layer, outcome, started, finished, detail) -> attempts.add(outcome);
        Stage2OutcomeHandoff handoff = (id, token, soFar, outcome, idempotent) -> handedOff.add(outcome);
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new LayerOneRouteBuilder(call("not-the-password"), writer, handoff,
                    new LayerOneWaits(3, Duration.ofMillis(10), Duration.ofMillis(20))));
            context.start();
            context.createProducerTemplate().sendBody("direct:stage2-send",
                    new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "order-1", UUID.randomUUID(), false));
        }

        assertThat(attempts).containsExactly(OutcomeClass.PERMANENT);
        assertThat(handedOff).containsExactly(OutcomeClass.PERMANENT);
    }
}
