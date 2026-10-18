package io.integrationplatform.e2e.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * Row 327 T151 (L-1, CWE-668), no Docker: the harness containers publish their ports on the loopback interface only,
 * so WireMock's {@code /__admin} API and the Artemis acceptor are not reachable from the LAN, and the Artemis web
 * console (8161) is not exposed at all. The containers' create-command modifiers are applied to a mocked
 * {@link CreateContainerCmd} and {@link HostConfig} (row 327 T155: no shaded Testcontainers internals).
 */
class E2eContainersTest {

    private static final String LOOPBACK = "127.0.0.1";

    @Test
    void artemisExposesOnlyItsAcceptorAndBindsItToLoopback() {
        GenericContainer<?> artemis = E2eContainers.artemis(Network.newNetwork());

        assertThat(artemis.getExposedPorts()).as("Artemis exposes the acceptor only (no 8161 console)")
                .containsExactly(E2eContainers.ARTEMIS_PORT);
        assertThat(hostIpsByPort(modifiersOf(artemis))).as("Artemis host port bindings")
                .containsExactlyEntriesOf(Map.of(E2eContainers.ARTEMIS_PORT, LOOPBACK));
    }

    @Test
    void wiremockBindsItsAdminPortToLoopback() {
        GenericContainer<?> wiremock = E2eContainers.wiremock(Network.newNetwork());

        assertThat(wiremock.getExposedPorts()).containsExactly(E2eContainers.WIREMOCK_PORT);
        assertThat(hostIpsByPort(modifiersOf(wiremock))).as("WireMock host port bindings")
                .containsExactlyEntriesOf(Map.of(E2eContainers.WIREMOCK_PORT, LOOPBACK));
    }

    @Test
    void loopbackOnlyBindsEveryGivenPortTo127001OnARandomHostPort() {
        assertThat(hostIpsByPort(E2eContainers.loopbackOnly(61616, 8080, 9000)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(61616, LOOPBACK, 8080, LOOPBACK, 9000, LOOPBACK));
    }

    /** Applies every create-command modifier of the container to the command it is given. */
    private static Consumer<CreateContainerCmd> modifiersOf(GenericContainer<?> container) {
        return cmd -> container.getCreateContainerCmdModifiers().forEach(modifier -> modifier.modify(cmd));
    }

    /** Container port -> host IP of every binding the modifiers put on a mocked create command's host config. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> hostIpsByPort(Consumer<CreateContainerCmd> modifiers) {
        CreateContainerCmd cmd = mock(CreateContainerCmd.class);
        HostConfig hostConfig = mock(HostConfig.class);
        when(cmd.getHostConfig()).thenReturn(hostConfig);

        modifiers.accept(cmd);

        ArgumentCaptor<List<PortBinding>> bindings = ArgumentCaptor.forClass(List.class);
        verify(hostConfig).withPortBindings(bindings.capture());
        return bindings.getValue().stream().collect(Collectors.toMap(
                binding -> binding.getExposedPort().getPort(),
                binding -> {
                    assertThat(binding.getBinding().getHostPortSpec()).as("host port left to Docker").isNull();
                    return binding.getBinding().getHostIp();
                }));
    }
}
