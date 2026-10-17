package io.integrationplatform.e2e.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * E15 seam (F17/T143): the harness's backing containers start together on one network and answer - WireMock's admin
 * health returns 200 and the broker reports itself up to {@code artemis check node --up} with the run's login (a
 * live node, not just an open socket). Tagged {@code container}: runs only under
 * {@code ./mvnw -Pcontainer test}, one Docker suite at a time (R06).
 */
@Tag("container")
class E2eHarnessContainerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void artemisAndWiremockStartOnOneNetworkAndAnswer() throws Exception {
        try (Network network = Network.newNetwork();
                GenericContainer<?> wiremock = E2eContainers.wiremock(network);
                GenericContainer<?> artemis = E2eContainers.artemis(network)) {
            wiremock.start();
            artemis.start();

            HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://" + wiremock.getHost()
                            + ":" + wiremock.getMappedPort(E2eContainers.WIREMOCK_PORT) + "/__admin/health"))
                    .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).as("WireMock admin health").isEqualTo(200);

            ExecResult nodeUp = artemis.execInContainer(E2eContainers.ARTEMIS_CLI, "check", "node", "--up",
                    "--user", E2eContainers.ARTEMIS_USER, "--password", E2eContainers.ARTEMIS_PASSWORD);
            assertThat(nodeUp.getExitCode()).as("artemis check node --up exit code; stdout: %s; stderr: %s",
                    nodeUp.getStdout(), nodeUp.getStderr()).isZero();
        }
    }
}
