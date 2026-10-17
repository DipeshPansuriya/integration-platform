package io.integrationplatform.config.flow.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Row 278 C2 (ADR-017 Decision 1): the refusal a publish listener throws carries its reason and its cause. */
class InterfacePublishRefusedTest {

    @Test
    void theRefusalKeepsTheReasonAndTheCause() {
        IllegalStateException cause = new IllegalStateException("detail");

        InterfacePublishRefused refused = new InterfacePublishRefused("the publish was refused", cause);

        assertThat(refused).isInstanceOf(RuntimeException.class);
        assertThat(refused.reason()).isEqualTo("the publish was refused");
        assertThat(refused.getCause()).isSameAs(cause);
    }
}
