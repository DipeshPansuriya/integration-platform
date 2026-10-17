package io.integrationplatform.e2e.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.shaded.com.github.dockerjava.core.command.CreateContainerCmdImpl;

/**
 * Row 327 T151 (L-1, CWE-668), no Docker: the harness containers publish their ports on the loopback interface only,
 * so WireMock's {@code /__admin} API and the Artemis acceptor are not reachable from the LAN, and the Artemis web
 * console (8161) is not exposed at all. The containers' own create-command modifiers are applied to a docker-java
 * command that is never sent to a daemon.
 */
class E2eContainersTest {

    private static final String LOOPBACK = "127.0.0.1";

    /** The command is only inspected, never executed against a Docker daemon. */
    private static final CreateContainerCmd.Exec NEVER_SENT = command -> {
        throw new AssertionError("a unit test never sends a create command to Docker");
    };

    @Test
    void artemisExposesOnlyItsAcceptorAndBindsItToLoopback() {
        GenericContainer<?> artemis = E2eContainers.artemis(Network.newNetwork());

        assertThat(artemis.getExposedPorts()).as("Artemis exposes the acceptor only (no 8161 console)")
                .containsExactly(E2eContainers.ARTEMIS_PORT);
        assertThat(hostIpsByPort(artemis)).as("Artemis host port bindings")
                .containsExactlyEntriesOf(Map.of(E2eContainers.ARTEMIS_PORT, LOOPBACK));
    }

    @Test
    void wiremockBindsItsAdminPortToLoopback() {
        GenericContainer<?> wiremock = E2eContainers.wiremock(Network.newNetwork());

        assertThat(wiremock.getExposedPorts()).containsExactly(E2eContainers.WIREMOCK_PORT);
        assertThat(hostIpsByPort(wiremock)).as("WireMock host port bindings")
                .containsExactlyEntriesOf(Map.of(E2eContainers.WIREMOCK_PORT, LOOPBACK));
    }

    /** Container port -> host IP of every binding the container's modifiers put on a create command. */
    private static Map<Integer, String> hostIpsByPort(GenericContainer<?> container) {
        CreateContainerCmd cmd = new CreateContainerCmdImpl(NEVER_SENT, null, "harness-unit-test");
        container.getCreateContainerCmdModifiers().forEach(modifier -> modifier.modify(cmd));
        Ports ports = cmd.getHostConfig().getPortBindings();
        assertThat(ports).as("the modifiers set host port bindings").isNotNull();
        return ports.getBindings().entrySet().stream().collect(Collectors.toMap(
                (Map.Entry<ExposedPort, Ports.Binding[]> entry) -> entry.getKey().getPort(),
                entry -> {
                    Ports.Binding[] bindings = entry.getValue();
                    assertThat(bindings).as("one random host port per container port").hasSize(1);
                    assertThat(bindings[0].getHostPortSpec()).as("host port left to Docker").isNull();
                    return Arrays.stream(bindings).map(Ports.Binding::getHostIp).findFirst().orElseThrow();
                }));
    }
}
