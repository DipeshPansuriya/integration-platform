package io.integrationplatform.routing.adapter.kafkatopic;

import java.time.Duration;
import java.util.UUID;

/**
 * Sends one record to the target's own topic and waits for the broker's ack. A broker refusal surfaces as the
 * Kafka error it is (PERMANENT); a broker that does not answer surfaces as a timeout or connect failure
 * (TRANSIENT). Thrown types are classified by {@link KafkaTopicTargetCall}.
 */
@FunctionalInterface
public interface KafkaSender {

    void send(KafkaTopicSettings settings, UUID deliveryId, byte[] body, Duration timeout) throws Exception;

    /**
     * E16: the send of a system that logs in with SASL. {@code saslPassword} is the call's own copy of the vault's
     * password (null for a system without SASL); the caller zeroes it afterwards. A sender with no security support
     * ignores it.
     */
    default void send(KafkaTopicSettings settings, char[] saslPassword, UUID deliveryId, byte[] body, Duration timeout)
            throws Exception {
        send(settings, deliveryId, body, timeout);
    }
}
