package io.integrationplatform.config.credential.application;

import io.integrationplatform.config.application.ConfigApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The credential API's refusals the config seam has no factory for: every validation error at once (400), and 503 - the identity provider could not create or
 * reach the client, or this instance holds no key-encryption key to seal with. Same body shape ({@code {"error": ...}})
 * and the same handler as every config refusal; the message is static or the provider's clear text, never a secret.
 */
public final class CredentialApiException extends ConfigApiException {

    private CredentialApiException(int httpStatus, Map<String, Object> body) {
        super(httpStatus, body);
    }

    /**
     * 400 with every validation error at once, in the systems registry's shape: {@code {"error": "<joined>",
     * "errors": [...]}}. An error names a field, never its value.
     */
    public static CredentialApiException invalid(List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", String.join("; ", errors));
        body.put("errors", List.copyOf(errors));
        return new CredentialApiException(400, body);
    }

    /** 503 with a clear message: try again later, or the deployment must add what is missing. */
    public static CredentialApiException unavailable(String message) {
        return new CredentialApiException(503, ConfigApiException.body("error", message));
    }
}
