package io.integrationplatform.routing.adapter.mq;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An MQ system's registry settings (host, port, queue - all required); a bad value is a configuration bug. */
public record MqSettings(String host, int port, String queue) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public MqSettings {
        if (host == null || host.isBlank() || queue == null || queue.isBlank()) {
            throw new IllegalStateException("MQ settings need host and queue");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("MQ port must be 1-65535");
        }
    }

    public static MqSettings fromJson(String json) {
        JsonNode node = JSON.readTree(json);
        JsonNode port = node.get("port");
        if (port == null || !port.isIntegralNumber()) {
            throw new IllegalStateException("MQ settings need an integer port");
        }
        return new MqSettings(text(node, "host"), port.intValue(), text(node, "queue"));
    }

    private static String text(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || !field.isTextual()) {
            throw new IllegalStateException("MQ settings need " + name + " as text");
        }
        return field.textValue();
    }
}
