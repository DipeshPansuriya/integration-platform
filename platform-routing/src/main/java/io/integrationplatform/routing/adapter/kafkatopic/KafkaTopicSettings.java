package io.integrationplatform.routing.adapter.kafkatopic;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-148: a KAFKA system's registry settings (bootstrapServers, topic - both required); a bad value is a
 * configuration bug. Shape follows {@code MqSettings.fromJson}.
 */
public record KafkaTopicSettings(String bootstrapServers, String topic) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public KafkaTopicSettings {
        bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers").strip();
        topic = Objects.requireNonNull(topic, "topic").strip();
        if (bootstrapServers.isBlank() || topic.isBlank()) {
            throw new IllegalStateException("Kafka settings need bootstrapServers and topic");
        }
        for (String server : bootstrapServers.split(",", -1)) {
            int at = server.lastIndexOf(':');
            int port;
            try {
                port = Integer.parseInt(server.substring(at + 1).strip());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Kafka bootstrap server '" + server + "' needs a port");
            }
            if (at <= 0 || server.substring(0, at).isBlank() || port < 1 || port > 65535) {
                throw new IllegalStateException("Kafka bootstrap server '" + server + "' must be host:port 1-65535");
            }
        }
    }

    public static KafkaTopicSettings fromJson(String json) {
        JsonNode node = JSON.readTree(json);
        return new KafkaTopicSettings(text(node, "bootstrapServers"), text(node, "topic"));
    }

    /** The host of the first bootstrap server - the one the test-connection probe dials. */
    public String host() {
        String first = bootstrapServers.split(",")[0].strip();
        return first.substring(0, first.lastIndexOf(':')).strip();
    }

    public int port() {
        String first = bootstrapServers.split(",")[0].strip();
        return Integer.parseInt(first.substring(first.lastIndexOf(':') + 1).strip());
    }

    private static String text(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || !field.isTextual()) {
            throw new IllegalStateException("Kafka settings need " + name + " as text");
        }
        return field.textValue();
    }
}
