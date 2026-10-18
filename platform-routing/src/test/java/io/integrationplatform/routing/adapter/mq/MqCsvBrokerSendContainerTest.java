package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T98 criteria 2 (on the queue) and 3 (broker down: five controls, Delivery retries): RED on purpose until a JMS
 * client is on the classpath - adding one is an architecture call (Needs decision). Not disabled, not faked.
 */
@Tag("container")
class MqCsvBrokerSendContainerTest {

    @Test
    void aJmsClientIsOnTheClasspathForTheBrokerSend() {
        assertThatCode(() -> Class.forName("org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory"))
                .as("Needs decision: org.apache.activemq:artemis-jakarta-client:2.55.0 (recommend "
                        + "spring-boot-starter-artemis) - then send CsvRenderer bytes to ArtemisContainers and "
                        + "stop the broker to prove the Delivery state machine retries")
                .doesNotThrowAnyException();
    }
}
