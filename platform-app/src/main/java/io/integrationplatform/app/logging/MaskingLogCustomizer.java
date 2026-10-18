package io.integrationplatform.app.logging;

import io.integrationplatform.config.flow.api.MaskedFields;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * T-066 / E12-F1-S2 (row 278 T108): every string value of every JSON log line - message, exception text, MDC field -
 * passes through {@link MaskedFields#scrub}, so a masked field's value intake registered never leaves the process in
 * a log line, whichever logger wrote it. Named by {@code logging.structured.json.customizer} in application.yml.
 */
public class MaskingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, MaskedFields::scrub));
    }
}
