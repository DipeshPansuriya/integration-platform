package io.integrationplatform.routing.adapter.file;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JacksonException;

/**
 * T-150: the production {@link FileOutbound} - the endpoint's FILE system settings plus the composition's mapped
 * file name and body. A malformed settings row is a configuration bug refused PERMANENT; logs nothing.
 */
final class FileTargetOutbound implements FileOutbound {

    private final Function<UUID, Optional<String>> settingsJson;
    private final FileTargetContent content;

    FileTargetOutbound(Function<UUID, Optional<String>> settingsJson, FileTargetContent content) {
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
        this.content = Objects.requireNonNull(content, "content");
    }

    @Override
    public Optional<FileMessage> resolve(Stage2Send send) {
        String json = settingsJson.apply(send.targetEndpointId()).orElse(null);
        if (json == null) {
            return Optional.empty();
        }
        FileSettings settings;
        try {
            settings = FileSettings.fromJson(json);
        } catch (IllegalStateException | JacksonException bad) {
            throw new TargetFaultException(OutcomeClass.PERMANENT,
                    "File settings unusable (" + bad.getClass().getSimpleName() + ")");
        }
        return content.contentFor(send).map(c -> new FileMessage(settings, c.fileName(), c.body()));
    }
}
