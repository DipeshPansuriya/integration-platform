package io.integrationplatform.config.mapping.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository.LiveMapping;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * T171 S5-A1 (security gate): the mapping save refuses hostile input with a 400 validation refusal BEFORE any
 * repository write - the size caps at their exact boundary, a null character anywhere in the text, links that are not
 * an array, and contract-version ids that are not UUIDs (the SQL casts them with {@code ::uuid}; unvalidated they were a
 * database error and a 500).
 */
class MappingAdminServiceValidationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ROUTE = "33333333-3333-3333-3333-333333333333";
    private static final String MAPPING = "44444444-4444-4444-4444-444444444444";
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String GOOD_ID = "55555555-5555-5555-5555-555555555555";

    private final JdbcMappingRepository mappings = mock(JdbcMappingRepository.class);
    private final MappingAdminService service = new MappingAdminService(mappings, event -> { },
            () -> Optional.of(UUID.randomUUID()));

    MappingAdminServiceValidationTest() {
        when(mappings.findMappingOfRoute(ROUTE)).thenReturn(Optional.of(
                new LiveMapping(MAPPING, "[]", "$", null, null, null)));
    }

    private void refused(String body, String messagePart) {
        org.mockito.Mockito.clearInvocations(mappings);
        assertThatThrownBy(() -> service.save(ROUTE, JSON.readTree(body), ACTOR))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).as("a validation refusal").isEqualTo(400);
                    assertThat(String.valueOf(e.body().get("error"))).contains(messagePart);
                });
        verify(mappings, never()).updateMappingOfRoute(any(), any(), any(), any(), any());
    }

    private static String expression(int chars) {
        return "{\"compiledExpression\":\"" + "x".repeat(chars) + "\"}";
    }

    @Test
    void anExpressionAtTheCapIsAcceptedAndOneCharacterOverIsRefused() {
        assertThatCode(() -> service.save(ROUTE, JSON.readTree(expression(MappingAdminService.MAX_EXPRESSION_CHARS)),
                ACTOR)).doesNotThrowAnyException();
        refused(expression(MappingAdminService.MAX_EXPRESSION_CHARS + 1), "longer than");
    }

    @Test
    void linksAtTheCapPassTheSizeGuardAndOneCharacterOverIsRefusedAsTooLong() {
        int cap = MappingAdminService.MAX_FIELD_MAPPINGS_CHARS;
        String atCap = "{\"compiledExpression\":\"$\",\"fieldMappings\":[\"" + "x".repeat(cap - 4) + "\"]}";
        // at the cap the size guard passes; the save is then refused for a different reason (links need bound versions)
        assertThatThrownBy(() -> service.save(ROUTE, JSON.readTree(atCap), ACTOR))
                .isInstanceOfSatisfying(ConfigApiException.class,
                        e -> assertThat(String.valueOf(e.body().get("error"))).doesNotContain("longer than"));
        refused("{\"compiledExpression\":\"$\",\"fieldMappings\":[\"" + "x".repeat(cap - 3) + "\"]}", "longer than");
    }

    @Test
    void aNullCharacterInTheExpressionIsRefused() {
        refused("{\"compiledExpression\":\"$\\u0000x\"}", "null character");
    }

    @Test
    void aNullCharacterInALinkIsRefused() {
        refused("{\"compiledExpression\":\"$\",\"fieldMappings\":[{\"source\":\"a\\u0000b\",\"target\":\"c\"}]}",
                "null character");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1}", "\"text\"", "7", "null"})
    void linksThatAreNotAnArrayAreRefused(String links) {
        refused("{\"compiledExpression\":\"$\",\"fieldMappings\":" + links + "}", "must be an array");
    }

    @Test
    void aMissingFieldMappingsDefaultsToAnEmptyArray() {
        assertThatCode(() -> service.save(ROUTE, JSON.readTree("{\"compiledExpression\":\"$\"}"), ACTOR))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", " ", "5555", "55555555-5555-5555-5555-55555555555g",
            "55555555555555555555555555555555", "5555\\u0000555-5555-5555-5555-555555555555",
            "'; drop table mapping; --"})
    void aContractVersionIdThatIsNotAUuidIsRefusedBeforeAnyWrite(String id) {
        String quoted = "\"" + id + "\"";
        refused("{\"compiledExpression\":\"$\",\"sourceContractVersionId\":" + quoted
                + ",\"targetContractVersionId\":\"" + GOOD_ID + "\"}", "UUID");
        refused("{\"compiledExpression\":\"$\",\"sourceContractVersionId\":\"" + GOOD_ID
                + "\",\"targetContractVersionId\":" + quoted + "}", "UUID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1}", "[1]", "true", "123"})
    void aContractVersionIdThatIsNotTextIsRefused(String json) {
        refused("{\"compiledExpression\":\"\",\"sourceContractVersionId\":" + json + ",\"targetContractVersionId\":\"" + GOOD_ID
                + "\"}", "UUID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", "1", "55555555-5555-5555-5555-55555555555g"})
    void aRouteIdThatIsNotAUuidIsNotFoundBeforeAnyDatabaseCast(String routeId) {
        assertThatThrownBy(() -> service.save(routeId, JSON.readTree("{\"compiledExpression\":\"\"}"), ACTOR))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.read(routeId)).isInstanceOfSatisfying(ConfigApiException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(404));
        verify(mappings, never()).findMappingOfRoute(routeId);
    }

    @Test
    void oneBoundVersionWithoutTheOtherIsRefused() {
        refused("{\"compiledExpression\":\"$\",\"sourceContractVersionId\":\"" + GOOD_ID + "\"}", "together");
    }
}
