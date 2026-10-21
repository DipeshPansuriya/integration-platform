package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.credential.api.TargetCredentialType;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.system.application.DestinationSettingsCheck;
import io.integrationplatform.config.system.application.SystemSettings;
import io.integrationplatform.config.system.application.SystemValidationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 (T-148/T-149/T-150) save-time contract of the three release-2 target types: the schema accepts exactly the
 * settings the adapters read, refuses a secret in the plain settings column, refuses a platform topic, and runs the
 * one destination guard over every host. Not container-tagged.
 */
class E16TargetSettingsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<String> errors(String type, String settings) {
        JsonNode typeNode = JSON.getNodeFactory().stringNode(type);
        return SystemSettings.validate(typeNode, JSON.readTree(settings));
    }

    @Test
    @DisplayName("KAFKA is a registered system type with bootstrapServers and topic")
    void kafkaIsARegisteredType() {
        assertThat(SystemSettings.TYPES).contains("KAFKA");
        assertThat(errors("KAFKA", "{\"bootstrapServers\":\"kafka.partner.example:9092\",\"topic\":\"orders-out\"}"))
                .isEmpty();
        assertThat(errors("KAFKA", "{}")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform.message.accepted", "platform.delivery.due", "platform.poison",
        "platform.anything", "PLATFORM.POISON"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A Kafka target can never be saved onto the platform's own topics")
    void aPlatformTopicIsRefusedAtSave(String topic) {
        assertThat(errors("KAFKA", "{\"bootstrapServers\":\"k.partner.example:9092\",\"topic\":\"" + topic + "\"}"))
                .singleElement().asString().contains("settings.topic");
    }

    @ParameterizedTest
    @ValueSource(strings = {"k.partner.example", "k.partner.example:0", "k.partner.example:99999", ":9092"})
    @Tag("negative")
    @DisplayName("Kafka bootstrapServers must be host:port, port 1-65535")
    void bootstrapServersNeedAPort(String servers) {
        assertThat(errors("KAFKA", "{\"bootstrapServers\":\"" + servers + "\",\"topic\":\"orders-out\"}"))
                .singleElement().asString().contains("settings.bootstrapServers");
    }

    @Test
    @DisplayName("The DB type carries its restricted account name and target table (the approved tables are no setting)")
    void dbCarriesTheTargetTableContract() {
        assertThat(errors("DB", "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\","
                + "\"user\":\"ipass_orders\",\"table\":\"orders_in\"}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"users\"]", "[]", "\"users\"", "null"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("E16 F3: approvedTables in the settings is refused FIELD_NOT_ALLOWED - tables are approved through the approval API, never in settings")
    void approvedTablesIsNoLongerASetting(String value) {
        List<String> found = errors("DB", "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\","
                + "\"user\":\"ipass_orders\",\"table\":\"orders_in\",\"approvedTables\":" + value + "}");
        assertThat(found).singleElement().asString().contains("settings.approvedTables")
                .contains(SystemSettings.FIELD_NOT_ALLOWED).contains("approved-tables");
        assertThat(new SystemValidationException(found).body()).containsEntry("refusal", "FIELD_NOT_ALLOWED");
    }

    @Test
    @Tag("negative")
    @DisplayName("A refusal that is not about approvedTables carries no refusal code")
    void otherValidationErrorsCarryNoRefusalCode() {
        assertThat(new SystemValidationException(List.of("settings.host is required")).body())
                .doesNotContainKey("refusal");
    }

    @Test
    @Tag("negative")
    @DisplayName("A DB system without its restricted account name is refused - it could only dead-letter (E16 C3)")
    void aDbSystemNeedsItsUser() {
        assertThat(errors("DB", "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\"}"))
                .singleElement().asString().contains("settings.user").contains("required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders_in; drop table x", "orders in", "public.orders_in", "1orders", "\\\"orders\\\""})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A DB table or approved-table entry must be a plain identifier - never an SQL fragment")
    void aTableMustBeAPlainIdentifier(String table) {
        String base = "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\",\"user\":\"ipass_orders\",";
        assertThat(errors("DB", base + "\"table\":\"" + table + "\"}"))
                .singleElement().asString().contains("settings.table");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "saslPassword", "secret", "token", "sasl.jaas.config"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A secret never rides in the plain settings of DB, Kafka or File - credentials are a separate seam")
    void aSecretIsRefusedInSettings(String key) {
        assertThat(errors("DB", "{\"host\":\"h.example\",\"port\":5432,\"database\":\"d\",\"user\":\"u\",\"" + key + "\":\"x\"}"))
                .singleElement().asString().contains(key);
        assertThat(errors("KAFKA", "{\"bootstrapServers\":\"h.example:9092\",\"topic\":\"t\",\"" + key + "\":\"x\"}"))
                .singleElement().asString().contains(key);
        assertThat(errors("FILE", "{\"rootPath\":\"/data/out\",\"" + key + "\":\"x\"}"))
                .singleElement().asString().contains(key);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Every Kafka bootstrap host goes through the one destination guard at save")
    void everyKafkaHostIsGuardedAtSave() {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.partner.example")), host -> {
            throw new AssertionError("the save-time check must not resolve " + host);
        });
        JsonNode bad = JSON.readTree("{\"bootstrapServers\":\"k1.partner.example:9092,169.254.169.254:9092\","
                + "\"topic\":\"orders-out\"}");
        assertThat(DestinationSettingsCheck.errors("KAFKA", bad, guard))
                .singleElement().asString().contains("settings.bootstrapServers").contains("link-local");
        JsonNode good = JSON.readTree("{\"bootstrapServers\":\"k1.partner.example:9092,k2.partner.example:9092\","
                + "\"topic\":\"orders-out\"}");
        assertThat(DestinationSettingsCheck.errors("KAFKA", good, guard)).isEmpty();
    }

    @Test
    @DisplayName("A KAFKA system's credentials are the KAFKA_TOPIC target's (SASL password), held in the vault")
    void kafkaCredentialsAreTheKafkaTopicTargets() {
        assertThat(TargetCredentialType.ofSystemType("KAFKA")).contains(TargetCredentialType.KAFKA_TOPIC);
    }
}
