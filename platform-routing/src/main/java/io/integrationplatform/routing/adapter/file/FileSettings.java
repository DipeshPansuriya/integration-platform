package io.integrationplatform.routing.adapter.file;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-150: a FILE system's registry settings (directory - required); a bad value is a configuration bug.
 * Shape follows {@code MqSettings.fromJson} / {@code KafkaTopicSettings.fromJson}. The file name comes per
 * message from the mapping, never from here, so {@code ..} tricks in it hit the path guard at send time.
 */
public record FileSettings(String directory) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public FileSettings {
        directory = Objects.requireNonNull(directory, "directory").strip();
        if (directory.isBlank()) {
            throw new IllegalStateException("File settings need directory");
        }
    }

    public static FileSettings fromJson(String json) {
        JsonNode node = JSON.readTree(json);
        JsonNode field = node.get("directory");
        if (field == null || !field.isTextual()) {
            throw new IllegalStateException("File settings need directory as text");
        }
        return new FileSettings(field.textValue());
    }
}