package io.integrationplatform.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.credential.api.KeyAvailability;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-007 Decision 7 rule 5 / ADR-010 section 9 item 4 (T-185) in the running application: the target-credential key
 * check is part of the READINESS group (config-secrets.yml), not of liveness - an instance that lacks a key a delivery
 * needed reports readiness OUT_OF_SERVICE (the load balancer takes it out) while liveness stays UP (nobody restarts
 * it; the deployment adds the key). The groups are read through the application's health endpoint; over HTTP only
 * {@code /actuator/health} is open today (the tenancy chain denies every other path - the probe paths are an Open item
 * of the T44 hand-back), and it answers 503 OUT_OF_SERVICE as well.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("E5-F1-S4 the instance fails its readiness check without a key")
class E5KeyReadinessContainerTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @LocalServerPort
    private int port;

    @Autowired
    private KeyAvailability keyAvailability;

    @Autowired
    private HealthEndpoint health;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PlatformTestWiring.register(registry, POSTGRES);
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        registry.add("PLATFORM_CREDENTIAL_KEK_K2", () -> Base64.getEncoder().encodeToString(key));
        registry.add("PLATFORM_CREDENTIAL_KEK_ACTIVE", () -> "K2");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Tag("negative")
    @DisplayName("Ready while every needed key is here; a delivery finding K1 missing makes readiness fail, liveness stays up")
    void readinessFollowsTheKeys() throws Exception {
        assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);

        keyAvailability.reportMissing("K1");

        assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.healthForPath("liveness").getStatus()).as("not restarted - the key is a deployment fix")
                .isEqualTo(Status.UP);
        HttpResponse<String> open = get("/actuator/health");
        assertThat(open.statusCode()).as(open.body()).isEqualTo(503);
        assertThat(open.body()).contains("OUT_OF_SERVICE");
    }
}
