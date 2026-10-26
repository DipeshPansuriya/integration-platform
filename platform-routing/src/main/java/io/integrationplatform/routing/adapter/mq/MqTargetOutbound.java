package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row 327: the production {@link MqOutbound} - the target's MQ system settings (host, port, queue, optional "user"),
 * the queue-manager password from the credential vault, and the body port. A malformed settings row is a
 * configuration bug and refused PERMANENT here (MqTargetCall would class a raw exception TRANSIENT). Logs nothing.
 */
final class MqTargetOutbound implements MqOutbound {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Function<UUID, Optional<String>> settingsJson;
    private final Function<UUID, Optional<char[]>> password;
    private final MqTargetBody body;

    MqTargetOutbound(Function<UUID, Optional<String>> settingsJson, Function<UUID, Optional<char[]>> password,
            MqTargetBody body) {
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
        this.password = Objects.requireNonNull(password, "password");
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public Optional<MqMessage> resolve(Stage2Send send) {
        String json = settingsJson.apply(send.targetEndpointId()).orElse(null);
        if (json == null) {
            return Optional.empty();
        }
        MqSettings settings;
        String user;
        try {
            settings = MqSettings.fromJson(json);
            JsonNode userNode = JSON.readTree(json).get("user");
            user = userNode != null && userNode.isTextual() ? userNode.textValue() : null;
        } catch (IllegalStateException | JacksonException bad) {
            // the class name only: a Jackson message can quote the row's text
            throw new TargetFaultException(OutcomeClass.PERMANENT,
                    "MQ settings unusable (" + bad.getClass().getSimpleName() + ")");
        }
        Optional<byte[]> rendered = body.bodyFor(send);
        if (rendered.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MqMessage(settings, user, password.apply(send.targetEndpointId()).orElse(null),
                rendered.get()));
    }
}
