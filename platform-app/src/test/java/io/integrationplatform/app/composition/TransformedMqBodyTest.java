package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.mq.MqTargetBody;
import io.integrationplatform.routing.stage2.Stage2Send;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Row 327: the MQ body is the transformed API body's bytes - same transform lookup, same tenant transaction. */
class TransformedMqBodyTest {

    private static final Stage2Send TRANSFORMED =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);
    private static final Stage2Send MISSING =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);
    private static final byte[] OUTPUT = "{\"order\":\"o-1\"}".getBytes(StandardCharsets.UTF_8);

    private final ApiTargetBody apiBody = send -> TRANSFORMED.equals(send)
            ? Optional.of(new ApiTargetBody.ApiWireBody("application/json", OUTPUT))
            : Optional.empty();

    private final MqTargetBody mqBody = new CompositionConfiguration().transformedMqBody(apiBody);

    @Test
    void mqSendCarriesTheTransformedBytes() {
        assertThat(mqBody.bodyFor(TRANSFORMED)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(OUTPUT));
    }

    @Test
    void missingTransformOrPayloadIsNoBody() {
        assertThat(mqBody.bodyFor(MISSING)).isEmpty();
    }
}
