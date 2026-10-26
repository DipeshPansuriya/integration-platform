package io.integrationplatform.app.composition;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.file.FileTargetContent;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 content wiring (decide-by-default, reversible by revert): the File adapter's body is the delivery's
 * transformed payload bytes (the same bytes Kafka/MQ/API send). The file name is the target system's optional
 * {@code fileNamePattern} ({@code {deliveryId}} and {@code {ext}} placeholders) or, without one,
 * {@code <deliveryId>.<ext>} - ext is {@code csv} for a csv content type, otherwise {@code json}. The delivery id in
 * the name is what makes a redelivery idempotent (FileWrite accepts the same bytes under the same name). The final
 * name is still checked by the adapter's plain-name guard at send time (no new trust); the folder is the
 * adapter's, never decided here.
 */
final class TransformedFileContent {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TransformedFileContent() {
    }

    static FileTargetContent of(ApiTargetBody body, Function<UUID, Optional<String>> settingsJson) {
        return send -> body.bodyFor(send).map(wire -> {
            String extension = wire.contentType().toLowerCase(Locale.ROOT).contains("csv") ? "csv" : "json";
            String pattern = pattern(settingsJson.apply(send.targetEndpointId()).orElse(null));
            if (pattern != null && !pattern.contains("{deliveryId}")) { // T178c-1: stored before the rule; never overwrite
                throw new TargetFaultException(OutcomeClass.PERMANENT,
                        "File name pattern has no {deliveryId}: edit the target system before it can send");
            }
            String name = pattern == null ? send.deliveryId() + "." + extension
                    : pattern.replace("{deliveryId}", send.deliveryId().toString()).replace("{ext}", extension);
            return new FileTargetContent.Content(name, wire.bytes());
        });
    }

    /** The configured pattern, or null when absent, blank or the settings cannot be read. */
    private static String pattern(String settingsJson) {
        if (settingsJson == null) {
            return null;
        }
        try {
            JsonNode field = JSON.readTree(settingsJson).get("fileNamePattern");
            return field != null && field.isString() && !field.stringValue().isBlank() ? field.stringValue() : null;
        } catch (JacksonException unreadable) {
            return null;
        }
    }
}
