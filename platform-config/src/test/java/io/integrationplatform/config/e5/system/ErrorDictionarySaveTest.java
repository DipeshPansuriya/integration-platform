package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-182 (E10-F1-S5, ADR-010 section 5; ADR-011 Decision 1 addendum 2026-10-20): the outcome table is the DEFAULT
 * and a target's error dictionary tunes it per status - but a rule the runtime refuses to bend is refused where
 * the target is SAVED: {@code OutcomeClassifier} ignores the dictionary for 400, 401, 403 and 422 and a 2xx can
 * only be SUCCESS. {@code halfOpenTrialCalls} is T-183's per-target cap (Resilience4j
 * {@code permittedNumberOfCallsInHalfOpenState}). Both live in {@code target_endpoint.settings} - validated on
 * save, no migration. Not tagged container (R03).
 */
class ErrorDictionarySaveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<String> errors(String settings) {
        JsonNode type = JSON.getNodeFactory().stringNode("API");
        return SystemSettings.validate(type, settings == null ? null : JSON.readTree(settings));
    }

    private static List<String> dictionary(String dictionaryJson) {
        return errors("{\"baseUrl\":\"https://api.partner.example/v1\",\"errorDictionary\":" + dictionaryJson + "}");
    }

    @Test
    @DisplayName("A documented 500 meaning \"not processed\": the TRANSIENT override saves")
    void transientOverrideIsAccepted() {
        assertThat(dictionary("{\"500\":\"TRANSIENT\"}")).isEmpty();
    }

    @ParameterizedTest(name = "{0} saved as {1} is refused")
    @CsvSource({"401,TRANSIENT", "401,AMBIGUOUS", "403,TRANSIENT", "403,AMBIGUOUS", "400,TRANSIENT", "422,TRANSIENT"})
    @DisplayName("The never-retryable refusals cannot be made retryable by a dictionary (ADR-010 section 5)")
    void neverRetryableOverrideIsRefusedAtSave(String status, String klass) {
        assertThat(dictionary("{\"" + status + "\":\"" + klass + "\"}")).singleElement()
                .asString().contains(status).contains("never retryable");
    }

    @Test
    @DisplayName("429 can never be PERMANENT - the target said \"later\"")
    void permanentRateLimitIsRefused() {
        assertThat(dictionary("{\"429\":\"PERMANENT\"}")).singleElement().asString().contains("429");
    }

    @Test
    @DisplayName("An override must change something: 503 is already TRANSIENT, and a 2xx is never re-classed")
    void pointlessAndSuccessOverridesAreRefused() {
        assertThat(dictionary("{\"503\":\"TRANSIENT\"}")).singleElement().asString().contains("already");
        assertThat(dictionary("{\"200\":\"PERMANENT\"}")).singleElement().asString().contains("200");
    }

    @Test
    @DisplayName("The dictionary is a closed map of status to outcome class - anything else is refused at save")
    void malformedDictionaryIsRefused() {
        assertThat(dictionary("\"not-an-object\"")).anyMatch(e -> e.contains("errorDictionary"));
        assertThat(dictionary("{\"500\":\"SOMETIMES\"}")).anyMatch(e -> e.contains("SOMETIMES"));
        assertThat(dictionary("{\"abc\":\"TRANSIENT\"}")).anyMatch(e -> e.contains("abc"));
        assertThat(dictionary("{\"999\":\"TRANSIENT\"}")).anyMatch(e -> e.contains("999"));
        assertThat(dictionary("{\"500\":\"\"}")).anyMatch(e -> e.contains("500"));
    }

    @Test
    @DisplayName("Every bad entry is listed, never only the first (the settings rule of T-102)")
    void everyEntryIsListed() {
        assertThat(dictionary("{\"401\":\"TRANSIENT\",\"403\":\"TRANSIENT\"}")).singleElement()
                .asString().contains("401").contains("403");
    }

    @ParameterizedTest(name = "halfOpenTrialCalls {0}")
    @CsvSource({"1,ok", "3,ok", "100,ok", "0,bad", "-1,bad", "101,bad"})
    @DisplayName("T-183's per-target trial cap is a whole number 1-100 (ADR-010 section 6; default 3)")
    void halfOpenTrialCallsIsBounded(int value, String verdict) {
        List<String> errors = errors("{\"baseUrl\":\"https://a.example/\",\"halfOpenTrialCalls\":" + value + "}");
        assertThat(errors).hasSize(verdict.equals("ok") ? 0 : 1);
    }

    @Test
    @DisplayName("A text trial cap is refused like any other wrong kind")
    void halfOpenTrialCallsMustBeANumber() {
        assertThat(errors("{\"baseUrl\":\"https://a.example/\",\"halfOpenTrialCalls\":\"3\"}")).singleElement()
                .asString().contains("halfOpenTrialCalls");
    }

    @Test
    @DisplayName("Both fields stay optional: the minimal API settings save as before")
    void fieldsAreOptional() {
        assertThat(errors("{\"baseUrl\":\"https://a.example/\"}")).isEmpty();
    }
}
