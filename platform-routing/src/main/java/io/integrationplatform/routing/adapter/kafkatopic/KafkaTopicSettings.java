package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-148: a KAFKA system's registry settings (bootstrapServers, topic - both required); a bad value is a
 * configuration bug. Shape follows {@code MqSettings.fromJson}.
 *
 * <p>E16 client security (all optional): {@code securityProtocol} (absent = SSL; PLAINTEXT only when written),
 * {@code saslMechanism} and {@code username} (both required with a SASL protocol, forbidden without) and
 * {@code truststore} (a file NAME under the operator's truststore directory; TLS only). The SASL password is NOT a
 * setting: it is the system's KAFKA_TOPIC credential in the vault. The record re-checks what the save checked, so a row
 * written around the API is still a PERMANENT configuration fault.
 */
public record KafkaTopicSettings(String bootstrapServers, String topic, String securityProtocol, String saslMechanism,
        String username, String truststore) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public KafkaTopicSettings(String bootstrapServers, String topic) {
        this(bootstrapServers, topic, null, null, null, null);
    }

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
        KafkaSecurityProtocol protocol = KafkaSecurityProtocol.resolve(securityProtocol);
        if (protocol.sasl()) {
            if (saslMechanism == null || !SystemSettings.KAFKA_SASL_MECHANISMS.contains(saslMechanism)) {
                throw new IllegalStateException("Kafka SASL needs saslMechanism "
                        + String.join(", ", SystemSettings.KAFKA_SASL_MECHANISMS));
            }
            if (!SystemSettings.isKafkaUser(username)) {
                throw new IllegalStateException("Kafka SASL needs a plain username");
            }
            if (protocol == KafkaSecurityProtocol.SASL_PLAINTEXT && saslMechanism.equals("PLAIN")) {
                throw new IllegalStateException("Kafka PLAIN over SASL_PLAINTEXT would send the password in clear");
            }
        } else if (saslMechanism != null || username != null) {
            throw new IllegalStateException("Kafka saslMechanism and username need a SASL securityProtocol");
        }
        if (truststore != null && (!protocol.tls() || !SystemSettings.isTruststoreName(truststore))) {
            throw new IllegalStateException("Kafka truststore must be a file name and needs a TLS securityProtocol");
        }
    }

    public static KafkaTopicSettings fromJson(String json) {
        JsonNode node = JSON.readTree(json);
        return new KafkaTopicSettings(text(node, "bootstrapServers"), text(node, "topic"),
                optionalText(node, "securityProtocol"), optionalText(node, "saslMechanism"),
                optionalText(node, "username"), optionalText(node, "truststore"));
    }

    /** The resolved security protocol (SSL when none is set). */
    KafkaSecurityProtocol protocol() {
        return KafkaSecurityProtocol.resolve(securityProtocol);
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

    private static String optionalText(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return null;
        }
        if (!field.isTextual()) {
            throw new IllegalStateException("Kafka settings need " + name + " as text");
        }
        return field.textValue();
    }
}
