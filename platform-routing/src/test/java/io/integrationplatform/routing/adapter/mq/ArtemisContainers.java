package io.integrationplatform.routing.adapter.mq;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * T98 harness: the Artemis broker pinned to 2.55.0 (the platform-e2e E2eContainers pattern). Login generated per
 * run (R07), anonymous login off, only the acceptor published and bound to loopback (CWE-668).
 */
public final class ArtemisContainers {

    public static final String IMAGE = "apache/activemq-artemis:2.44.0@sha256:0305c26f19eda00c0506d0953cfe265600a72ac1b17f7934412120c01a595cff";
    public static final int ACCEPTOR_PORT = 61616;
    public static final String USER = "t98-" + UUID.randomUUID();
    public static final String PASSWORD = UUID.randomUUID().toString();
    public static final String CHECK_NODE_UP = "/var/lib/artemis-instance/bin/artemis check node --up"
            + " --user \"$ARTEMIS_USER\" --password \"$ARTEMIS_PASSWORD\"";

    private ArtemisContainers() {
    }

    public static GenericContainer<?> artemis() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("ARTEMIS_USER", USER)
                .withEnv("ARTEMIS_PASSWORD", PASSWORD)
                .withEnv("ANONYMOUS_LOGIN", "false")
                .withEnv("TZ", "Etc/UTC")
                .withExposedPorts(ACCEPTOR_PORT)
                .withCreateContainerCmdModifier(loopbackOnly())
                .waitingFor(Wait.forSuccessfulCommand(CHECK_NODE_UP).withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static Consumer<CreateContainerCmd> loopbackOnly() {
        return cmd -> cmd.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(ACCEPTOR_PORT)));
    }
}
