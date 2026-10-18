package io.integrationplatform.config.flow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-066 / E12-F1-S2 (row 278 T108): the masked-field paths a flow declares, the values they pick out of one payload,
 * and the redaction that keeps those values out of logs and the tracking API. Not tagged container (R03).
 */
class MaskedFieldsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void anAbsentOrNullMaskedFieldsListIsEmptyNotAnError() {
        assertThat(MaskedFields.parse(json("{\"name\":\"x\"}"))).isEmpty();
        assertThat(MaskedFields.parse(json("{\"maskedFields\":null}"))).isEmpty();
        assertThat(MaskedFields.parse(null)).isEmpty();
    }

    @Test
    void aMaskedFieldsListIsReadAsTrimmedPaths() {
        assertThat(MaskedFields.parse(json("{\"maskedFields\":[\"customerId\",\" $.card.number \"]}")))
                .contains(List.of("customerId", "$.card.number"));
    }

    @Test
    void aMaskedFieldsValueThatIsNotAnArrayOfNonBlankStringsIsRefused() {
        assertThatThrownBy(() -> MaskedFields.parse(json("{\"maskedFields\":\"customerId\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maskedFields");
        assertThatThrownBy(() -> MaskedFields.parse(json("{\"maskedFields\":[1]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaskedFields.parse(json("{\"maskedFields\":[\" \"]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStoredJsonTextReadsBackAsTheSamePaths() {
        assertThat(MaskedFields.fromJson("[\"customerId\",\"a.b\"]")).containsExactly("customerId", "a.b");
        assertThat(MaskedFields.fromJson(null)).isEmpty();
        assertThat(MaskedFields.toJson(List.of("customerId"))).isEqualTo("[\"customerId\"]");
    }

    @Test
    void thePathsPickTheScalarValuesOutOfThePayloadNestedAndInArrays() {
        JsonNode payload = json("{\"customerId\":\"MASK-PROBE-7Q3Z\",\"orderId\":\"o-1\","
                + "\"card\":{\"number\":\"4111111111111111\",\"cvv\":123},"
                + "\"lines\":[{\"sku\":\"s1\"},{\"sku\":\"s2\"}]}");

        assertThat(MaskedFields.valuesOf(payload, List.of("customerId", "$.card", "lines.sku", "missing.path")))
                .containsExactlyInAnyOrder("MASK-PROBE-7Q3Z", "4111111111111111", "123", "s1", "s2");
        assertThat(MaskedFields.valuesOf(payload, List.of())).isEmpty();
        assertThat(MaskedFields.valuesOf(null, List.of("customerId"))).isEmpty();
    }

    @Test
    void redactReplacesEveryOccurrenceOfEachValueWithTheMarker() {
        String redacted = MaskedFields.redact("HTTP 400 customerId MASK-PROBE-7Q3Z rejected (MASK-PROBE-7Q3Z)",
                List.of("MASK-PROBE-7Q3Z"));

        assertThat(redacted).doesNotContain("MASK-PROBE-7Q3Z")
                .isEqualTo("HTTP 400 customerId " + MaskedFields.MARKER + " rejected (" + MaskedFields.MARKER + ")");
        assertThat(MaskedFields.redact(null, List.of("x"))).isNull();
        assertThat(MaskedFields.redact("nothing here", List.of())).isEqualTo("nothing here");
    }

    @Test
    void aRegisteredValueIsScrubbedFromAnyLaterText() {
        MaskedFields.register(List.of("REGISTRY-PROBE-81XK"));

        assertThat(MaskedFields.scrub("target said REGISTRY-PROBE-81XK is unknown"))
                .isEqualTo("target said " + MaskedFields.MARKER + " is unknown");
        assertThat(MaskedFields.scrub(null)).isNull();
    }
}
