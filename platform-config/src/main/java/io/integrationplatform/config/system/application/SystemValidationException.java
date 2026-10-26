package io.integrationplatform.config.system.application;

import io.integrationplatform.config.application.ConfigApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 400 with EVERY validation error of a system registration or edit (E5-F1-S1): {@code {"error": "<all errors, joined>",
 * "errors": ["...", "..."]}} - the {@code error} key keeps the config refusal shape every client already reads, and
 * {@code errors} lists each problem on its own for the screens.
 */
public final class SystemValidationException extends ConfigApiException {

    public SystemValidationException(List<String> errors) {
        super(400, body(errors));
    }

    private static Map<String, Object> body(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("a validation refusal names at least one error");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", String.join("; ", errors));
        body.put("errors", List.copyOf(errors));
        if (errors.stream().anyMatch(error -> error.contains(SystemSettings.FIELD_NOT_ALLOWED))) {
            body.put("refusal", SystemSettings.FIELD_NOT_ALLOWED); // E16 F3: an old client learns the field moved
        }
        return body;
    }
}
