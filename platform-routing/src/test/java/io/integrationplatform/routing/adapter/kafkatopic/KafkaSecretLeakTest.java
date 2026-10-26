package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * E16: the SASL password reaches the producer and nothing else. Drives the REAL kafka-clients producer (config
 * validation, login-module setup and the failed connect all run, only no broker answers) through the target call with
 * everything logged at DEBUG captured: the password must be in no log line, no exception message and no cause chain,
 * and the value objects that carry it must not print it.
 */
@Tag("security")
class KafkaSecretLeakTest {

    private static final String SECRET = "Zx9-unmistakable-secret-77";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;
    private Level before;

    @BeforeEach
    void captureEverything() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        before = root.getLevel();
        root.setLevel(Level.DEBUG);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void restore() {
        root.detachAppender(logs);
        root.setLevel(before);
    }

    private static String everyLine(List<ILoggingEvent> events) {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : events) {
            all.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                all.append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }
        return all.toString();
    }

    @Test
    void theSecretIsInNoLogLineAndNoFaultWhenTheBrokerCannotBeReached() {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("localhost")),
                host -> List.of(InetAddress.getByName("127.0.0.1")), List.of("localhost"), List.of(1));
        KafkaTopicSettings settings = KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"localhost:1\","
                + "\"topic\":\"orders-out\",\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"SCRAM-SHA-256\","
                + "\"username\":\"svc-ipass\"}");
        KafkaTopicTargetCall call = new KafkaTopicTargetCall(
                send -> Optional.of(new KafkaMessage(settings, new byte[] {1}, SECRET.toCharArray())),
                new KafkaClientsSender(guard), guard, Set.of("platform-work"), CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP, Duration.ofMillis(1500));

        Throwable thrown = null;
        try {
            call.call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));
        } catch (TargetFaultException fault) {
            thrown = fault;
            assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.TRANSIENT); // nothing answered
        }

        assertThat(thrown).isNotNull();
        for (Throwable each = thrown; each != null; each = each.getCause()) {
            assertThat(String.valueOf(each.getMessage())).doesNotContain(SECRET);
        }
        assertThat(everyLine(logs.list)).isNotBlank().doesNotContain(SECRET);
        // the producer really was configured for SASL (kafka logs its config, with the password hidden)
        assertThat(everyLine(logs.list)).contains("sasl.mechanism = SCRAM-SHA-256").contains("[hidden]");
    }

    @Test
    void theValueObjectsThatCarryItDoNotPrintIt() {
        KafkaTopicSettings settings = KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"k.example:9093\","
                + "\"topic\":\"t\",\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\",\"username\":\"u\"}");
        KafkaMessage message = new KafkaMessage(settings, new byte[] {1}, SECRET.toCharArray());

        assertThat(message.toString()).doesNotContain(SECRET);
        assertThat(settings.toString()).doesNotContain(SECRET);
        assertThatThrownBy(() -> KafkaClientsSender.producerProperties("k.example:9093", Duration.ofSeconds(5),
                settings, null, null)).hasMessageNotContaining(SECRET);
    }

    @Test
    void theMessageWipesTheSecretAfterTheCall() {
        char[] secret = SECRET.toCharArray();
        KafkaMessage message = new KafkaMessage(KafkaTopicSettings.fromJson(
                "{\"bootstrapServers\":\"k.example:9093\",\"topic\":\"t\"}"), new byte[] {1}, secret);

        message.wipe();

        assertThat(secret).containsOnly('\0');
        assertThat(message.body()).containsOnly(0);
    }

    @Test
    void theCallPassesTheVaultPasswordToTheSenderAndWipesItAfterwards() {
        List<String> seen = new ArrayList<>();
        char[] secret = SECRET.toCharArray();
        KafkaTopicSettings settings = KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"localhost:9093\","
                + "\"topic\":\"orders-out\",\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\","
                + "\"username\":\"u\"}");
        KafkaSender sender = new KafkaSender() {
            @Override
            public void send(KafkaTopicSettings s, UUID id, byte[] body, Duration timeout) {
                throw new AssertionError("a SASL send must use the password-carrying overload");
            }

            @Override
            public void send(KafkaTopicSettings s, char[] saslPassword, UUID id, byte[] body, Duration timeout) {
                seen.add(new String(saslPassword));
            }
        };
        DestinationGuard guard = KafkaTopicTargetCallControlsTest.guard();

        new KafkaTopicTargetCall(send -> Optional.of(new KafkaMessage(settings, new byte[] {1}, secret)), sender,
                guard, Set.of("platform-work"), CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, Duration.ofSeconds(5))
                .call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));

        assertThat(seen).containsExactly(SECRET);
        assertThat(secret).containsOnly('\0');
    }
}
