package io.integrationplatform.e2e.harness;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.integrationplatform.e2e.E2eImages;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * E15 seam (F17/T143): the harness's backing containers, the same images as compose's {@code e2e} profile
 * ({@link E2eImages}). Plain {@link GenericContainer}s - the repo convention (platform-config's WireMockDestination),
 * no extra Testcontainers module. Each joins the given network under its compose service name, so a product
 * container on that network reaches them as {@code artemis:61616} and {@code wiremock:8080}.
 *
 * <p>Host ports are published on the loopback interface only (row 327 T151, L-1 / CWE-668): WireMock's
 * {@code /__admin} API and the Artemis acceptor are never reachable from the LAN, and the Artemis web console is not
 * exposed.
 *
 * <p>Not started here: the caller owns the lifecycle (one Docker suite at a time, R06).
 */
public final class E2eContainers {

    /** Artemis core/AMQP/OpenWire acceptor. */
    public static final int ARTEMIS_PORT = 61616;

    /** WireMock's own port inside the container. */
    public static final int WIREMOCK_PORT = 8080;

    /** Artemis login, generated per run (R07: never a literal credential in a file). */
    public static final String ARTEMIS_USER = "e2e-" + UUID.randomUUID();

    /** Artemis password, generated per run (R07). */
    public static final String ARTEMIS_PASSWORD = UUID.randomUUID().toString();

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private static final String LOOPBACK = "127.0.0.1";

    /** Artemis CLI inside the image (the compose healthcheck calls the same binary). */
    public static final String ARTEMIS_CLI = "/var/lib/artemis-instance/bin/artemis";

    /** Readiness probe; the shell inside the container expands the login from the env set below. */
    private static final String ARTEMIS_CHECK_NODE_UP =
            ARTEMIS_CLI + " check node --up --user \"$ARTEMIS_USER\" --password \"$ARTEMIS_PASSWORD\"";

    private E2eContainers() {
    }

    /**
     * The JMS broker, anonymous login off, ready once {@code artemis check node --up} succeeds with the run's login -
     * the same probe as compose's {@code e2e}-profile healthcheck, so the harness and compose agree on "ready" (the
     * console's startup log line no longer applies: the console is not exposed).
     */
    public static GenericContainer<?> artemis(Network network) {
        return new GenericContainer<>(DockerImageName.parse(E2eImages.ARTEMIS))
                .withNetwork(network)
                .withNetworkAliases("artemis")
                .withEnv("ARTEMIS_USER", ARTEMIS_USER)
                .withEnv("ARTEMIS_PASSWORD", ARTEMIS_PASSWORD)
                .withEnv("ANONYMOUS_LOGIN", "false")
                .withEnv("TZ", "Etc/UTC")
                .withExposedPorts(ARTEMIS_PORT)
                .withCreateContainerCmdModifier(loopbackOnly(ARTEMIS_PORT))
                .waitingFor(Wait.forSuccessfulCommand(ARTEMIS_CHECK_NODE_UP).withStartupTimeout(STARTUP_TIMEOUT));
    }

    /** The stand-in HTTP destination, ready once its admin health answers 200. */
    public static GenericContainer<?> wiremock(Network network) {
        return new GenericContainer<>(DockerImageName.parse(E2eImages.WIREMOCK))
                .withNetwork(network)
                .withNetworkAliases("wiremock")
                .withEnv("TZ", "Etc/UTC")
                .withExposedPorts(WIREMOCK_PORT)
                .withCreateContainerCmdModifier(loopbackOnly(WIREMOCK_PORT))
                .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200).withStartupTimeout(STARTUP_TIMEOUT));
    }

    /** Publishes each container port on a random host port bound to 127.0.0.1 only (not 0.0.0.0). */
    static Consumer<CreateContainerCmd> loopbackOnly(int... containerPorts) {
        return cmd -> cmd.getHostConfig().withPortBindings(Arrays.stream(containerPorts)
                .mapToObj(port -> new PortBinding(Ports.Binding.bindIp(LOOPBACK), new ExposedPort(port)))
                .toList());
    }
}
