package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-102 (E5-F1-S1): each system type's settings schema (ADR-011 Decision 1: a JSON Schema shipped in code, validated on
 * every save) - the minimal required settings per type, and EVERY validation error listed, never just the first.
 * Not tagged container (R03).
 */
class SystemSettingsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return text == null ? null : JSON.readTree(text);
    }

    private static List<String> errors(String type, String settings) {
        return SystemSettings.validate(type == null ? null : JSON.getNodeFactory().stringNode(type), json(settings));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "API  | {\"baseUrl\":\"https://api.partner.example/v1\"}",
        "API  | {\"baseUrl\":\"http://api.partner.example:8080\",\"timeoutMs\":2500,\"followRedirects\":true}",
        "DB   | {\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\",\"user\":\"ipass_orders\"}",
        "MQ   | {\"host\":\"mq.partner.example\",\"port\":1414,\"queue\":\"ORDERS.IN\"}",
        "FILE | {\"rootPath\":\"/data/outbound\"}",
        "FILE | {\"rootPath\":\"D:/exchange/out\"}"})
    @DisplayName("Each type's minimal settings are accepted")
    void validSettingsPerType(String type, String settings) {
        assertThat(errors(type.trim(), settings)).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("An unknown type is refused by name")
    void anUnknownTypeIsRefused() {
        assertThat(errors("FTP", "{\"host\":\"ftp.partner.example\"}"))
                .singleElement().asString().contains("type").contains("FTP").contains("API, DB, MQ, FILE");
    }

    @Test
    @Tag("negative")
    @DisplayName("An API system without a base address is refused")
    void anApiWithoutABaseAddressIsRefused() {
        assertThat(errors("API", "{}")).singleElement().asString().contains("settings").contains("baseUrl");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"ftp://api.partner.example/", "api.partner.example", "https://", "file:///etc"})
    @Tag("negative")
    @DisplayName("An API base address must be an http or https address with a host")
    void anApiBaseAddressMustBeHttp(String address) {
        assertThat(errors("API", "{\"baseUrl\":\"" + address + "\"}"))
                .isNotEmpty().allSatisfy(error -> assertThat(error).contains("baseUrl"));
    }

    @Test
    @Tag("negative")
    @DisplayName("Every missing setting is listed, not only the first")
    void everyErrorIsListed() {
        List<String> errors = errors("DB", "{}");
        assertThat(errors).hasSize(4);
        assertThat(String.join("\n", errors)).contains("host").contains("port").contains("database").contains("user");
    }

    @Test
    @Tag("negative")
    @DisplayName("Wrong types and out-of-range values are each listed")
    void wrongTypesAndRanges() {
        List<String> errors = errors("MQ", "{\"host\":\"\",\"port\":70000,\"queue\":7}");
        assertThat(errors).hasSize(3);
        assertThat(String.join("\n", errors)).contains("host").contains("port").contains("queue");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Unknown settings are refused - a secret never rides in the plain settings column")
    void unknownSettingsAreRefused() {
        assertThat(errors("API", "{\"baseUrl\":\"https://a.example/\",\"password\":\"x\"}"))
                .singleElement().asString().contains("password");
    }

    @Test
    @Tag("negative")
    @DisplayName("A type needs its settings, and settings need a type")
    void typeAndSettingsGoTogether() {
        assertThat(errors("API", null)).singleElement().asString().contains("settings").contains("required");
        assertThat(errors(null, "{\"baseUrl\":\"https://a.example/\"}"))
                .singleElement().asString().contains("type").contains("required");
        assertThat(errors("API", "[1,2]")).singleElement().asString().contains("settings").contains("object");
    }

    @Test
    @DisplayName("No type and no settings is the legacy E3 registration: nothing to validate")
    void noTypeNoSettingsIsValid() {
        assertThat(errors(null, null)).isEmpty();
    }

    // ---------- T-182 / T-183 (E10-F1-S5): the error dictionary and the half-open trial count ----------

    private static final String API = "\"baseUrl\":\"https://a.example/\"";

    @Test
    @DisplayName("T-182: an API target may override the class of a status, and set its half-open trial count")
    void anErrorDictionaryAndATrialCountAreAccepted() {
        assertThat(errors("API", "{" + API + ",\"errorDictionary\":{\"500\":\"TRANSIENT\",\"404\":\"PERMANENT\","
                + "\"409\":\"AMBIGUOUS\"},\"halfOpenTrialCalls\":5}")).isEmpty();
    }

    @ParameterizedTest(name = "{0} as TRANSIENT")
    @CsvSource({"400", "401", "403", "422"})
    @Tag("negative")
    @DisplayName("T-182: an override can never make 400, 401, 403 or 422 retryable - the save is refused with the reason")
    void anOverrideCannotMakeARefusalRetryable(String status) {
        assertThat(errors("API", "{" + API + ",\"errorDictionary\":{\"" + status + "\":\"TRANSIENT\"}}"))
                .singleElement().asString().contains("errorDictionary").contains(status).contains("never retryable");
    }

    @Test
    @Tag("negative")
    @DisplayName("T-182: a status outside 100-599, an unknown class, SUCCESS and a non-object are each refused")
    void aMalformedDictionaryIsRefused() {
        assertThat(errors("API", "{" + API + ",\"errorDictionary\":{\"99\":\"TRANSIENT\",\"abc\":\"TRANSIENT\","
                + "\"500\":\"MAYBE\",\"502\":\"SUCCESS\"}}")).hasSize(4)
                .allSatisfy(error -> assertThat(error).contains("errorDictionary"));
        assertThat(errors("API", "{" + API + ",\"errorDictionary\":[1]}")).singleElement().asString()
                .contains("errorDictionary").contains("object");
    }

    @ParameterizedTest(name = "halfOpenTrialCalls {0}")
    @CsvSource({"0", "-1", "101", "2.5"})
    @Tag("negative")
    @DisplayName("T-183: the half-open trial count is a whole number from 1 to 100")
    void theTrialCountIsBounded(String value) {
        assertThat(errors("API", "{" + API + ",\"halfOpenTrialCalls\":" + value + "}"))
                .singleElement().asString().contains("halfOpenTrialCalls").contains("1 to 100");
    }

    @Test
    @DisplayName("T-183: every type may carry the trial count; only an API target has a status dictionary")
    void theTrialCountIsOnEveryTypeTheDictionaryOnlyOnApi() {
        assertThat(errors("DB", "{\"host\":\"h\",\"port\":5432,\"database\":\"d\",\"user\":\"u\",\"halfOpenTrialCalls\":2}")).isEmpty();
        assertThat(errors("FILE", "{\"rootPath\":\"/o\",\"halfOpenTrialCalls\":2}")).isEmpty();
        assertThat(errors("FILE", "{\"rootPath\":\"/o\",\"errorDictionary\":{\"500\":\"TRANSIENT\"}}"))
                .singleElement().asString().contains("errorDictionary");
    }
}
