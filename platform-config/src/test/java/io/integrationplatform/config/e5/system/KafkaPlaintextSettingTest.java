package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 Karan R1/R2, save time: a tenant cannot choose clear-text Kafka (PLAINTEXT, SASL_PLAINTEXT) unless the operator
 * set {@code platform.config.destinations.kafka-allow-plaintext}; SSL, SASL_SSL and "no protocol" (= SSL) always save.
 */
class KafkaPlaintextSettingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "\"bootstrapServers\":\"k.partner.example:9093\",\"topic\":\"orders-out\"";
    private static final String SASL = ",\"saslMechanism\":\"SCRAM-SHA-512\",\"username\":\"svc\"";

    private static List<String> errors(String extra, Boolean allowPlaintext) {
        var type = JSON.getNodeFactory().stringNode("KAFKA");
        var settings = JSON.readTree("{" + BASE + extra + "}");
        return allowPlaintext == null ? SystemSettings.validate(type, settings)
                : SystemSettings.validate(type, settings, allowPlaintext);
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"securityProtocol\":\"PLAINTEXT\"", ",\"securityProtocol\":\"SASL_PLAINTEXT\"" + SASL})
    @Tag("security")
    @Tag("negative")
    void clearTextIsRefusedAtSaveByDefault(String extra) {
        assertThat(errors(extra, null)).singleElement().asString().contains("settings.securityProtocol")
                .contains("kafka-allow-plaintext");
        assertThat(errors(extra, false)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"securityProtocol\":\"PLAINTEXT\"", ",\"securityProtocol\":\"SASL_PLAINTEXT\"" + SASL})
    void theOperatorSwitchLetsClearTextSave(String extra) {
        assertThat(errors(extra, true)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"securityProtocol\":\"SSL\"", ",\"securityProtocol\":\"SASL_SSL\"" + SASL})
    void tlsAndTheDefaultAlwaysSave(String extra) {
        assertThat(errors(extra, null)).isEmpty();
        assertThat(errors(extra, false)).isEmpty();
    }
}
