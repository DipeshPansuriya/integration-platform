package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Row 327: an MQ send whose transform or payload is missing is refused PERMANENT, and the fault names no value. */
class MqMissingBodyTest {

    private static final String SECRET = "s3cr3t-pw";
    private static final String SETTINGS =
            "{\"host\":\"localhost\",\"port\":61616,\"queue\":\"orders.q\",\"user\":\"app-user\"}";

    @Test
    void missingBodyIsPermanentWithNoValuesInTheMessage() {
        Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "order-key-1", UUID.randomUUID(), false);
        MqTargetOutbound outbound = new MqTargetOutbound(id -> Optional.of(SETTINGS),
                id -> Optional.of(SECRET.toCharArray()), s -> Optional.empty());
        MqTargetCall call = new MqTargetCall(outbound, MqTargetCallControlsTest.loopbackGuard(61616),
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.create(),
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> org.assertj.core.api.Assertions.assertThat(fault.outcomeClass())
                                .isEqualTo(OutcomeClass.PERMANENT))
                .message().doesNotContain(SECRET).doesNotContain("order-key-1").doesNotContain("orders.q")
                .doesNotContain("app-user");
    }
}
