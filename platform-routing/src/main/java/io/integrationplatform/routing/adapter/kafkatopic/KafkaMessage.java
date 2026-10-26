package io.integrationplatform.routing.adapter.kafkatopic;

import java.util.Arrays;

/**
 * One due delivery resolved for a Kafka send: its target settings, the record body and - for a SASL system only - the
 * password the vault opened for this call. The password is the call's own copy: {@link #wipe()} zeroes it, and it is
 * never part of {@link #toString()}.
 */
public record KafkaMessage(KafkaTopicSettings settings, byte[] body, char[] saslPassword) {

    public KafkaMessage(KafkaTopicSettings settings, byte[] body) {
        this(settings, body, null);
    }

    /** Zeroes the body and the password. */
    public void wipe() {
        Arrays.fill(body, (byte) 0);
        if (saslPassword != null) {
            Arrays.fill(saslPassword, '\0');
        }
    }

    @Override
    public String toString() {
        return "KafkaMessage[topic=" + settings.topic() + ", bytes=" + body.length + "]";
    }
}
