package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.SystemTopicRefusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-184 Karan SF-2: a KAFKA system's save refuses the platform's own record topics and a tenant-named
 * platform-prefixed name, with the reason; a proper tenant topic saves. Not tagged container (R03).
 */
class SystemTopicRefusalTest {

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"platform.message.accepted", "platform.delivery.due", "platform.poison",
        "platform.messages", "platform_custom_topic"})
    @DisplayName("The platform's register is never a tenant destination (SF-2)")
    void platformTopicsAreRefused(String topic) {
        assertThat(SystemTopicRefusal.topicProblem(topic)).isPresent()
                .asString().contains("platform");
    }

    @Test
    @DisplayName("A blank or non-topic name is refused at save")
    void malformedTopicsAreRefused() {
        assertThat(SystemTopicRefusal.topicProblem(null)).isPresent();
        assertThat(SystemTopicRefusal.topicProblem("   ")).isPresent();
        assertThat(SystemTopicRefusal.topicProblem("")).isPresent();
        assertThat(SystemTopicRefusal.topicProblem("bad/topic")).isPresent();
        assertThat(SystemTopicRefusal.topicProblem("orders topic")).isPresent();
    }

    @Test
    @DisplayName("A proper tenant topic saves: no refusal reason")
    void tenantTopicSaves() {
        assertThat(SystemTopicRefusal.topicProblem("orders.topic-1")).isEmpty();
        assertThat(SystemTopicRefusal.topicProblem("ORDERS.IN_2026")).isEmpty();
    }
}