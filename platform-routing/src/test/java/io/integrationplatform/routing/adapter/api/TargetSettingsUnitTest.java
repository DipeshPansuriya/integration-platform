package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E10 T96 unit level (no container): the half-open cap's value rules and the error-dictionary overlay. The SQL and its
 * tenant scoping are proved on the real schema in {@link TargetSettingsContainerTest}.
 */
class TargetSettingsUnitTest {

    @ParameterizedTest
    @CsvSource({"1,1", "3,3", "5,5", "100,100", "' 7 ',7"})
    @DisplayName("a halfOpenTrialCalls from 1 to 100 is the cap")
    void aValueInRangeIsTheCap(String stored, int expected) {
        assertThat(JdbcTargetHalfOpenCap.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-3", "101", "1000000", "abc", "5.5", "5.0", "99999999999", "true", "null"})
    @Tag("negative")
    @DisplayName("absent, blank, out of 1..100 or not a whole number: the default 3, never an exception")
    void anythingElseIsTheDefault(String stored) {
        assertThat(JdbcTargetHalfOpenCap.parse(stored)).isEqualTo(3);
    }

    @Test
    @Tag("negative")
    @DisplayName("a system without the halfOpenTrialCalls key (the row's value is SQL NULL) is the default 3, not a NullPointerException")
    void aRowWithANullValueIsTheDefault() {
        assertThat(JdbcTargetHalfOpenCap.fromRows(java.util.Arrays.asList((String) null))).isEqualTo(3);
        assertThat(JdbcTargetHalfOpenCap.fromRows(java.util.List.of())).isEqualTo(3);
        assertThat(JdbcTargetHalfOpenCap.fromRows(java.util.List.of("5"))).isEqualTo(5);
    }

    @Test
    @DisplayName("the column alone reads as before")
    void theColumnAlone() {
        assertThat(JdbcTargetEndpointViews.dictionary("{\"503\":\"TRANSIENT\",\"418\":\"permanent\"}", null))
                .isEqualTo(Map.of(503, OutcomeClass.TRANSIENT, 418, OutcomeClass.PERMANENT));
    }

    @Test
    @DisplayName("the settings alone is read when the column is empty")
    void theSettingsAlone() {
        assertThat(JdbcTargetEndpointViews.dictionary("{}", "{\"502\":\"TRANSIENT\"}"))
                .isEqualTo(Map.of(502, OutcomeClass.TRANSIENT));
        assertThat(JdbcTargetEndpointViews.dictionary(null, "{\"502\":\"AMBIGUOUS\"}"))
                .isEqualTo(Map.of(502, OutcomeClass.AMBIGUOUS));
    }

    @Test
    @DisplayName("a status in both takes the settings class; entries unique to either are kept")
    void settingsWinOverTheColumn() {
        assertThat(JdbcTargetEndpointViews.dictionary("{\"503\":\"PERMANENT\",\"418\":\"PERMANENT\"}",
                "{\"503\":\"TRANSIENT\",\"502\":\"TRANSIENT\"}")).isEqualTo(
                        Map.of(503, OutcomeClass.TRANSIENT, 502, OutcomeClass.TRANSIENT, 418, OutcomeClass.PERMANENT));
    }

    @Test
    @DisplayName("no settings change nothing: absent, blank, JSON null or a non-object leave the column result")
    void absentSettingsChangeNothing() {
        Map<Integer, OutcomeClass> column = Map.of(503, OutcomeClass.TRANSIENT);
        for (String settings : new String[] {null, "", "  ", "null", "\"text\"", "[1,2]", "5"}) {
            assertThat(JdbcTargetEndpointViews.dictionary("{\"503\":\"TRANSIENT\"}", settings))
                    .as("settings=%s", settings).isEqualTo(column);
        }
        assertThat(JdbcTargetEndpointViews.dictionary(null, null)).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("a malformed entry still fails loudly, naming the key (no silent drop of a classification)")
    void aMalformedEntryFails() {
        assertThatThrownBy(() -> JdbcTargetEndpointViews.dictionary(null, "{\"abc\":\"TRANSIENT\"}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("abc");
        assertThatThrownBy(() -> JdbcTargetEndpointViews.dictionary(null, "{\"503\":\"MAYBE\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
