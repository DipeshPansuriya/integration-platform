package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.security.JaasContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * E16 Kafka client security, no broker: the producer Properties for every protocol and SASL mechanism, the password
 * reaching the JAAS line exactly (no injection), the hostname check always on, and a truststore that cannot leave the
 * operator's directory. The password comes from the vault as a char[]; it is never part of the settings.
 */
class KafkaSecurityTest {

    private static final char[] PASSWORD = "pw-4f1c9a".toCharArray();

    private static KafkaTopicSettings settings(String extra) {
        return KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"k.partner.example:9093\",\"topic\":\"orders-out\""
                + (extra.isEmpty() ? "" : "," + extra) + "}");
    }

    private static Properties props(KafkaTopicSettings settings, char[] password, Path truststoreDir) {
        return KafkaClientsSender.producerProperties("k.partner.example:9093", Duration.ofSeconds(5), settings,
                password, truststoreDir);
    }

    private static AppConfigurationEntry jaas(Properties props) {
        JaasContext context = JaasContext.loadClientContext(
                Map.of("sasl.jaas.config", new Password(props.getProperty("sasl.jaas.config"))));
        assertThat(context.configurationEntries()).hasSize(1);
        return context.configurationEntries().get(0);
    }

    @Test
    void noSecurityFieldMeansTlsNotClearText() {
        Properties props = props(settings(""), null, null);

        assertThat(props.getProperty("security.protocol")).isEqualTo("SSL");
        assertThat(props.getProperty("ssl.endpoint.identification.algorithm")).isEqualTo("https");
        assertThat(props).doesNotContainKeys("sasl.mechanism", "sasl.jaas.config", "ssl.truststore.location");
    }

    @Test
    @Tag("security")
    void plaintextIsOnlyWhatIsExplicitlySet() {
        Properties props = props(settings("\"securityProtocol\":\"PLAINTEXT\""), null, null);

        assertThat(props.getProperty("security.protocol")).isEqualTo("PLAINTEXT");
        assertThat(props.stringPropertyNames()).noneMatch(k -> k.startsWith("ssl.") || k.startsWith("sasl."));
    }

    @Test
    @Tag("security")
    void tlsAlwaysVerifiesTheHostname() {
        for (String protocol : List.of("SSL", "SASL_SSL")) {
            String sasl = protocol.equals("SASL_SSL") ? ",\"saslMechanism\":\"PLAIN\",\"username\":\"svc\"" : "";
            Properties props = props(settings("\"securityProtocol\":\"" + protocol + "\"" + sasl), PASSWORD, null);

            assertThat(props.getProperty("security.protocol")).isEqualTo(protocol);
            assertThat(props.getProperty("ssl.endpoint.identification.algorithm")).isEqualTo("https");
            assertThat(props.getProperty("ssl.enabled.protocols")).isEqualTo("TLSv1.3,TLSv1.2");
        }
    }

    @ParameterizedTest
    @CsvSource({"PLAIN,org.apache.kafka.common.security.plain.PlainLoginModule",
        "SCRAM-SHA-256,org.apache.kafka.common.security.scram.ScramLoginModule",
        "SCRAM-SHA-512,org.apache.kafka.common.security.scram.ScramLoginModule"})
    void saslSslCarriesTheMechanismUserAndVaultPassword(String mechanism, String module) {
        Properties props = props(settings("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"" + mechanism
                + "\",\"username\":\"svc-ipass\""), PASSWORD, null);

        assertThat(props.getProperty("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(props.getProperty("sasl.mechanism")).isEqualTo(mechanism);
        AppConfigurationEntry entry = jaas(props);
        assertThat(entry.getLoginModuleName()).isEqualTo(module);
        assertThat(new java.util.HashMap<String, Object>(entry.getOptions())).containsEntry("username", "svc-ipass").containsEntry("password", "pw-4f1c9a");
    }

    @Test
    void scramOverSaslPlaintextHasNoTlsProperties() {
        Properties props = props(settings("\"securityProtocol\":\"SASL_PLAINTEXT\",\"saslMechanism\":"
                + "\"SCRAM-SHA-512\",\"username\":\"svc\""), PASSWORD, null);

        assertThat(props.getProperty("security.protocol")).isEqualTo("SASL_PLAINTEXT");
        assertThat(props.getProperty("sasl.mechanism")).isEqualTo("SCRAM-SHA-512");
        assertThat(props.stringPropertyNames()).noneMatch(k -> k.startsWith("ssl."));
    }

    @Test
    @Tag("security")
    void aPasswordCannotBreakOutOfItsJaasValue() {
        String hostile = "p\"w;x} y\nz\"; org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"admin\" password=\"admin\\";
        Properties props = props(settings("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\","
                + "\"username\":\"svc\""), hostile.toCharArray(), null);

        AppConfigurationEntry entry = jaas(props); // exactly one login module entry
        assertThat(new java.util.HashMap<String, Object>(entry.getOptions())).containsEntry("password", hostile).containsEntry("username", "svc");
    }

    @Test
    @Tag("negative")
    void saslWithoutAVaultPasswordIsRefusedWithoutNamingAnything() {
        KafkaTopicSettings sasl = settings("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\","
                + "\"username\":\"svc\"");

        assertThatThrownBy(() -> props(sasl, null, null)).isInstanceOf(KafkaSecurityException.class)
                .hasMessageContaining("credential").hasMessageNotContaining("svc");
        assertThatThrownBy(() -> props(sasl, new char[0], null)).isInstanceOf(KafkaSecurityException.class);
    }

    @Test
    void aTruststoreIsLoadedFromTheOperatorsDirectoryByItsFileType(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("partner-ca.p12"), "x");
        Files.writeString(dir.resolve("legacy-ca.jks"), "x");

        Properties p12 = props(settings("\"truststore\":\"partner-ca.p12\""), null, dir);
        Properties jks = props(settings("\"truststore\":\"legacy-ca.jks\""), null, dir);

        assertThat(Path.of(p12.getProperty("ssl.truststore.location"))).isEqualTo(dir.resolve("partner-ca.p12"));
        assertThat(p12.getProperty("ssl.truststore.type")).isEqualTo("PKCS12");
        assertThat(jks.getProperty("ssl.truststore.type")).isEqualTo("JKS");
        assertThat(p12).doesNotContainKey("ssl.truststore.password");
    }

    @Test
    @Tag("negative")
    void aTruststoreWithoutADirectoryOrFileIsAConfigurationFault(@TempDir Path dir) {
        KafkaTopicSettings named = settings("\"truststore\":\"missing-ca.p12\"");

        assertThatThrownBy(() -> props(named, null, null)).isInstanceOf(KafkaSecurityException.class)
                .hasMessageContaining("truststore directory");
        assertThatThrownBy(() -> props(named, null, dir)).isInstanceOf(KafkaSecurityException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Tag("security")
    void settingsRefuseATruststorePathEvenIfSaveWasBypassed() {
        assertThatThrownBy(() -> settings("\"truststore\":\"../../etc/hosts\""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("\"securityProtocol\":\"PLAINTEXT\",\"truststore\":\"a.p12\""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("negative")
    void theSettingsRecordRefusesHalfConfiguredSasl() {
        assertThatThrownBy(() -> settings("\"securityProtocol\":\"SASL_SSL\",\"username\":\"svc\""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("\"securityProtocol\":\"SASL_PLAINTEXT\",\"saslMechanism\":\"PLAIN\","
                + "\"username\":\"svc\"")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("\"securityProtocol\":\"GSSAPI\"")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void kafkaClientsAcceptsEveryPropertySetItBuilds(@TempDir Path dir) {
        for (String extra : List.of("\"securityProtocol\":\"PLAINTEXT\"", "\"securityProtocol\":\"SSL\"",
                "\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\",\"username\":\"u\"",
                "\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"SCRAM-SHA-256\",\"username\":\"u\"",
                "\"securityProtocol\":\"SASL_PLAINTEXT\",\"saslMechanism\":\"SCRAM-SHA-512\",\"username\":\"u\"")) {
            Properties props = props(settings(extra), PASSWORD.clone(), dir);
            props.put("bootstrap.servers", "127.0.0.1:1");
            // the constructor validates the whole config and opens no connection, so no broker is needed
            try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
                assertThat(producer).isNotNull();
            }
        }
    }
}
