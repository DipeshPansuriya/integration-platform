package io.integrationplatform.routing.adapter.kafkatopic;

/** One due delivery resolved for a Kafka send: its target settings and the record body. */
public record KafkaMessage(KafkaTopicSettings settings, byte[] body) {
}
