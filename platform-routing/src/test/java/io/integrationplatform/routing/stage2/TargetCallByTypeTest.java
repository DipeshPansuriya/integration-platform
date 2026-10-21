package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Row 327: stage 2 picks the target call by the endpoint's system type; an unknown type is PERMANENT, never dropped. */
class TargetCallByTypeTest {

    private final List<String> called = new ArrayList<>();
    private final TargetCall mq = send -> called.add("MQ");
    private final TargetCall api = send -> called.add("API");

    private TargetCall selector(Optional<String> type) {
        return TargetCall.byType(endpointId -> type, Map.of("MQ", mq, "API", api));
    }

    private static Stage2Send send() {
        return new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);
    }

    @Test
    void mqTargetGoesToTheMqCall() {
        selector(Optional.of("MQ")).call(send());
        assertThat(called).containsExactly("MQ");
    }

    @Test
    void apiTargetGoesToTheApiCall() {
        selector(Optional.of("API")).call(send());
        assertThat(called).containsExactly("API");
    }

    @Test
    void unknownTypeIsPermanent() {
        assertThatThrownBy(() -> selector(Optional.of("FTP")).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        e -> assertThat(e.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(called).isEmpty();
    }

    @Test
    void missingTypeIsPermanent() {
        assertThatThrownBy(() -> selector(Optional.empty()).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        e -> assertThat(e.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(called).isEmpty();
    }
}
