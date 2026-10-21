package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 Kafka client security, save time: the KAFKA settings carry the security protocol, SASL mechanism, user name and
 * a truststore FILE NAME - never a secret (the SASL password is the KAFKA_TOPIC credential of the OS-store vault) -
 * and the combinations that would send a password in clear or half-configure SASL are refused at save.
 */
class KafkaSecuritySettingsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "\"bootstrapServers\":\"k.partner.example:9093\",\"topic\":\"orders-out\"";

    private static List<String> errors(String extra) {
        // the operator's clear-text switch is ON here: these tests are about the other cross-field rules
        // (KafkaPlaintextSettingTest covers the switch itself)
        return SystemSettings.validate(JSON.getNodeFactory().stringNode("KAFKA"),
                JSON.readTree("{" + BASE + (extra.isEmpty() ? "" : "," + extra) + "}"), true);
    }

    @Test
    @DisplayName("No security field saves as before - the runtime default is SSL, PLAINTEXT only when set explicitly")
    void noSecurityFieldIsStillValid() {
        assertThat(errors("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAINTEXT", "SSL"})
    void plainAndSslNeedNoSasl(String protocol) {
        assertThat(errors("\"securityProtocol\":\"" + protocol + "\"")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"})
    void saslSslAcceptsEveryMechanism(String mechanism) {
        assertThat(errors("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"" + mechanism
                + "\",\"username\":\"svc-ipass\"")).isEmpty();
    }

    @Test
    void scramOverSaslPlaintextIsAllowed() {
        assertThat(errors("\"securityProtocol\":\"SASL_PLAINTEXT\",\"saslMechanism\":\"SCRAM-SHA-512\","
                + "\"username\":\"svc\"")).isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("PLAIN over SASL_PLAINTEXT would send the password in clear - refused")
    void plainOverPlaintextIsRefused() {
        assertThat(errors("\"securityProtocol\":\"SASL_PLAINTEXT\",\"saslMechanism\":\"PLAIN\",\"username\":\"svc\""))
                .singleElement().asString().contains("settings.saslMechanism").contains("clear");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLS", "ssl", "SASL", "", "GSSAPI"})
    @Tag("negative")
    void anUnknownProtocolIsRefused(String protocol) {
        assertThat(errors("\"securityProtocol\":\"" + protocol + "\""))
                .singleElement().asString().contains("settings.securityProtocol");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GSSAPI", "OAUTHBEARER", "plain", "SCRAM-SHA-1", ""})
    @Tag("negative")
    void anUnknownMechanismIsRefused(String mechanism) {
        assertThat(errors("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"" + mechanism
                + "\",\"username\":\"svc\"")).anyMatch(e -> e.contains("settings.saslMechanism"));
    }

    @Test
    @Tag("negative")
    @DisplayName("SASL needs a mechanism and a user name; a mechanism or user without SASL is refused")
    void saslFieldsGoTogether() {
        assertThat(errors("\"securityProtocol\":\"SASL_SSL\",\"username\":\"svc\""))
                .singleElement().asString().contains("settings.saslMechanism");
        assertThat(errors("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\""))
                .singleElement().asString().contains("settings.username");
        assertThat(errors("\"securityProtocol\":\"SSL\",\"saslMechanism\":\"PLAIN\""))
                .singleElement().asString().contains("settings.saslMechanism");
        assertThat(errors("\"saslMechanism\":\"PLAIN\",\"username\":\"svc\"")).isNotEmpty();
        assertThat(errors("\"securityProtocol\":\"SSL\",\"username\":\"svc\""))
                .singleElement().asString().contains("settings.username");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\\\"; evil", "x\ny", "", "  "})
    @Tag("negative")
    @Tag("security")
    void aUserNameIsPlainText(String username) {
        assertThat(errors("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\",\"username\":"
                + JSON.writeValueAsString(username))).anyMatch(e -> e.startsWith("settings.username"));
    }

    @Test
    void aTruststoreIsAFileNameUnderTheOperatorsDirectory() {
        assertThat(errors("\"securityProtocol\":\"SSL\",\"truststore\":\"partner-ca.p12\"")).isEmpty();
        assertThat(errors("\"truststore\":\"partner-ca.jks\"")).isEmpty(); // the default protocol is SSL
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "/etc/ssl/ca.p12", "C:\\ca.p12", "dir/ca.p12", ".", "..", "a b.p12"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A truststore is never a path - no traversal out of the operator's directory")
    void aTruststorePathIsRefused(String truststore) {
        assertThat(errors("\"securityProtocol\":\"SSL\",\"truststore\":" + JSON.writeValueAsString(truststore)))
                .anyMatch(e -> e.startsWith("settings.truststore"));
    }

    @Test
    @Tag("negative")
    void aTruststoreNeedsTls() {
        assertThat(errors("\"securityProtocol\":\"PLAINTEXT\",\"truststore\":\"ca.p12\""))
                .singleElement().asString().contains("settings.truststore");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "saslPassword", "sasl.jaas.config", "keystorePassword", "truststorePassword",
        "ssl.truststore.password", "secret", "token", "sslEndpointIdentificationAlgorithm"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A secret or an unsafe client property never rides in the Kafka settings")
    void aSecretOrClientPropertyIsRefused(String key) {
        assertThat(errors("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\",\"username\":\"svc\",\""
                + key + "\":\"x\"")).singleElement().asString().contains("settings." + key)
                .contains("not a known KAFKA setting").doesNotContain("\"x\"");
    }
}
