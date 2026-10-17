package io.integrationplatform.e2e.harness;

import io.integrationplatform.e2e.E2eImages;
import java.time.Duration;
import java.util.UUID;
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
 * <p>Not started here: the caller owns the lifecycle (one Docker suite at a time, R06).
 */
public final class E2eContainers {

    /** Artemis core/AMQP/OpenWire acceptor. */
    public static final int ARTEMIS_PORT = 61616;

    /** Artemis web console (its startup log line is the readiness signal). */
    public static final int ARTEMIS_CONSOLE_PORT = 8161;

    /** WireMock's own port inside the container. */
    public static final int WIREMOCK_PORT = 8080;

    /** Artemis login, generated per run (R07: never a literal credential in a file). */
    public static final String ARTEMIS_USER = "e2e-" + UUID.randomUUID();

    /** Artemis password, generated per run (R07). */
    public static final String ARTEMIS_PASSWORD = UUID.randomUUID().toString();

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private E2eContainers() {
    }

    /** The JMS broker, anonymous login off, ready once its embedded web server has started. */
    public static GenericContainer<?> artemis(Network network) {
        return new GenericContainer<>(DockerImageName.parse(E2eImages.ARTEMIS))
                .withNetwork(network)
                .withNetworkAliases("artemis")
                .withEnv("ARTEMIS_USER", ARTEMIS_USER)
                .withEnv("ARTEMIS_PASSWORD", ARTEMIS_PASSWORD)
                .withEnv("ANONYMOUS_LOGIN", "false")
                .withEnv("TZ", "Etc/UTC")
                .withExposedPorts(ARTEMIS_PORT, ARTEMIS_CONSOLE_PORT)
                .waitingFor(Wait.forLogMessage(".*HTTP Server started.*", 1).withStartupTimeout(STARTUP_TIMEOUT));
    }

    /** The stand-in HTTP destination, ready once its admin health answers 200. */
    public static GenericContainer<?> wiremock(Network network) {
        return new GenericContainer<>(DockerImageName.parse(E2eImages.WIREMOCK))
                .withNetwork(network)
                .withNetworkAliases("wiremock")
                .withEnv("TZ", "Etc/UTC")
                .withExposedPorts(WIREMOCK_PORT)
                .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200).withStartupTimeout(STARTUP_TIMEOUT));
    }
}
