package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.junit.jupiter.api.Test;

/**
 * E16: where the SASL password comes from (the vault's KAFKA_TOPIC credential, opened per send and only for a SASL
 * system) and how a credential or TLS failure is classified: a retry cannot fix a wrong password or an untrusted
 * certificate and could lock the account, so both are PERMANENT.
 */
class KafkaTargetCredentialTest {

    private static final String SASL = "{\"bootstrapServers\":\"broker.local:9093\",\"topic\":\"orders-out\","
            + "\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\",\"username\":\"svc\"}";
    private static final String TLS_ONLY = "{\"bootstrapServers\":\"broker.local:9093\",\"topic\":\"orders-out\"}";
    private static final Stage2Send SEND =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);

    private static KafkaTargetOutbound outbound(String settings, java.util.function.Function<UUID,
            Optional<char[]>> password) {
        return new KafkaTargetOutbound(id -> Optional.of(settings),
                send -> Optional.of("a,b".getBytes(StandardCharsets.UTF_8)), password);
    }

    @Test
    void aSaslSystemGetsItsPasswordFromTheVaultSeam() {
        KafkaMessage message = outbound(SASL, id -> Optional.of("vault-pw".toCharArray())).resolve(SEND).orElseThrow();

        assertThat(message.saslPassword()).containsExactly("vault-pw".toCharArray());
        assertThat(message.toString()).doesNotContain("vault-pw");
    }

    @Test
    void aSystemWithoutSaslNeverOpensTheVault() {
        AtomicInteger opened = new AtomicInteger();

        outbound(TLS_ONLY, id -> {
            opened.incrementAndGet();
            return Optional.of("x".toCharArray());
        }).resolve(SEND).orElseThrow();

        assertThat(opened).hasValue(0);
    }

    @Test
    void aSaslSystemWithNoStoredPasswordIsPermanent() {
        assertThatThrownBy(() -> outbound(SASL, id -> Optional.empty()).resolve(SEND))
                .isInstanceOfSatisfying(TargetFaultException.class, fault -> {
                    assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
                    assertThat(fault.getMessage()).contains("credential");
                });
    }

    @Test
    void theTwoArgumentOutboundHasNoPasswordAndRefusesSasl() {
        KafkaTargetOutbound plain = new KafkaTargetOutbound(id -> Optional.of(SASL), send -> Optional.of(new byte[1]));

        assertThatThrownBy(() -> plain.resolve(SEND)).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }

    @Test
    void aWrongPasswordIsPermanentNotRetried() {
        assertThat(outcomeOf(new SaslAuthenticationException("Authentication failed")))
                .isEqualTo(OutcomeClass.PERMANENT);
    }

    @Test
    void anUntrustedCertificateIsPermanentNotRetried() {
        assertThat(outcomeOf(new SslAuthenticationException("handshake failed"))).isEqualTo(OutcomeClass.PERMANENT);
    }

    @Test
    void anOrdinaryConnectFailureStaysTransient() {
        assertThat(outcomeOf(new org.apache.kafka.common.KafkaException("connect failed")))
                .isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    void aSecurityConfigurationFaultIsPermanent() {
        assertThat(outcomeOf(new KafkaSecurityException("the truststore directory is not configured")))
                .isEqualTo(OutcomeClass.PERMANENT);
    }

    private static OutcomeClass outcomeOf(Exception failure) {
        KafkaTopicSettings settings = KafkaTopicSettings.fromJson(TLS_ONLY.replace("broker.local", "localhost"));
        KafkaSender sender = (s, id, body, timeout) -> {
            throw failure;
        };
        KafkaTopicTargetCall call = new KafkaTopicTargetCall(
                send -> Optional.of(new KafkaMessage(settings, new byte[1])), sender,
                KafkaTopicTargetCallControlsTest.guard(), Set.of("platform-work"), CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP, Duration.ofSeconds(5));
        try {
            call.call(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false));
            throw new AssertionError("expected a fault");
        } catch (TargetFaultException fault) {
            return fault.outcomeClass();
        }
    }
}
