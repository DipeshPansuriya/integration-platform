package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row 330: the production {@link KafkaOutbound} - the target's KAFKA system settings (bootstrapServers, topic) and the
 * rendered body. A malformed settings row is a configuration bug and refused PERMANENT here. Logs nothing.
 * Idempotent: the delivery id is the record key in {@code KafkaClientsSender}, so retries land on the same message.
 */
final class KafkaTargetOutbound implements KafkaOutbound {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final java.util.function.Function<UUID, Optional<String>> settingsJson;
    private final KafkaTargetBody body;

    /** E16: the SASL password per endpoint from the vault seam - opened only for a SASL system, the call zeroes it. */
    private final java.util.function.Function<UUID, Optional<char[]>> saslPassword;

    KafkaTargetOutbound(java.util.function.Function<UUID, Optional<String>> settingsJson, KafkaTargetBody body) {
        this(settingsJson, body, id -> Optional.empty());
    }

    KafkaTargetOutbound(java.util.function.Function<UUID, Optional<String>> settingsJson, KafkaTargetBody body,
            java.util.function.Function<UUID, Optional<char[]>> saslPassword) {
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
        this.body = Objects.requireNonNull(body, "body");
        this.saslPassword = Objects.requireNonNull(saslPassword, "saslPassword");
    }

    @Override
    public Optional<KafkaMessage> resolve(Stage2Send send) {
        String json = settingsJson.apply(send.targetEndpointId()).orElse(null);
        if (json == null) {
            return Optional.empty();
        }
        KafkaTopicSettings settings;
        try {
            settings = KafkaTopicSettings.fromJson(json);
        } catch (IllegalStateException | JacksonException bad) {
            throw new TargetFaultException(OutcomeClass.PERMANENT,
                    "Kafka settings unusable (" + bad.getClass().getSimpleName() + ")");
        }
        Optional<byte[]> rendered = body.bodyFor(send);
        if (rendered.isEmpty()) {
            return Optional.empty();
        }
        char[] password = null;
        if (settings.protocol().sasl()) {
            password = saslPassword.apply(send.targetEndpointId()).filter(p -> p.length > 0).orElse(null);
            if (password == null) {
                throw new TargetFaultException(OutcomeClass.PERMANENT,
                        "the SASL credential of target endpoint " + send.targetEndpointId() + " is not stored");
            }
        }
        return Optional.of(new KafkaMessage(settings, rendered.get(), password));
    }
}
