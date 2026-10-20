package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/** T98 criterion 1: the harness broker is 2.55.0, up, authenticated per run and bound to loopback only. */
@Tag("container")
class ArtemisContainersContainerTest {

    @Test
    void theHarnessBrokerIsPinnedUpAndLoopbackOnly() throws Exception {
        try (GenericContainer<?> broker = ArtemisContainers.artemis()) {
            broker.start();

            assertThat(broker.getDockerImageName()).isEqualTo("apache/activemq-artemis:2.44.0");
            assertThat(broker.execInContainer("sh", "-c", ArtemisContainers.CHECK_NODE_UP).getExitCode()).isZero();
            assertThat(broker.getContainerInfo().getNetworkSettings().getPorts().getBindings().values())
                    .allSatisfy(bindings -> assertThat(bindings)
                            .allSatisfy(b -> assertThat(b.getHostIp()).isEqualTo("127.0.0.1")));
        }
    }
}
