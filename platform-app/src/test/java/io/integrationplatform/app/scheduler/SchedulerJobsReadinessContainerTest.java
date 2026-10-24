package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.IntPredicate;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T198 (B35) in the real boot: the {@code schedulerJobs} readiness check flags a scheduler schema the app login
 * cannot use while the application otherwise runs. Read through the root {@code /actuator/health} (the one open
 * path; the group paths /actuator/health/readiness and /actuator/health/liveness are read by the T209 tests below).
 * {@code show-details} is unset, so an anonymous caller gets the status and
 * nothing else.
 *
 * <pre>
 * Scenario: Readiness flags a missing scheduler grant
 *   Given the app starts with the app login granted ipaas_app
 *   Then /actuator/health answers 200 UP
 *   When usage on the scheduler schema is revoked from ipaas_app (the B35 fault)
 *   Then /actuator/health answers 503 and the anonymous body is exactly {"status":"OUT_OF_SERVICE"}
 * </pre>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SchedulerJobsReadinessContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static PlatformTestWiring.RestrictedLogin login;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @Test
    @DisplayName("A normal start is ready: the job register is complete")
    void normalStartIsReady() throws Exception {
        HttpResponse<String> response = awaitStatus(code -> code == 200);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).matches(statusOnly("UP"));
    }

    @Test
    @Tag("negative")
    @DisplayName("B35: no usage on the scheduler schema is OUT_OF_SERVICE (503), and the anonymous body is the status only")
    void missingSchedulerGrantIsOutOfService() throws Exception {
        awaitStatus(code -> code == 200);
        owner("REVOKE USAGE ON SCHEMA scheduler FROM ipaas_app");
        try {
            HttpResponse<String> response = awaitStatus(code -> code == 503);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(response.body()).matches(statusOnly("OUT_OF_SERVICE"));
        } finally {
            owner("GRANT USAGE ON SCHEMA scheduler TO ipaas_app");
        }
    }

    @Test
    @DisplayName("T209: anonymous readiness and liveness answer 200 with exactly the status, nothing else")
    void anonymousProbeBodiesAreTheStatusOnly() throws Exception {
        awaitStatus(code -> code == 200);

        for (String probe : new String[] {"readiness", "liveness"}) {
            HttpResponse<String> response = get("/actuator/health/" + probe);
            assertThat(response.statusCode()).as("%s: %s", probe, response.body()).isEqualTo(200);
            assertThat(response.body()).as(probe + " body").isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("T209: anonymous readiness under the B35 fault is 503 with the status only - no job names, no reason")
    void anonymousReadinessUnderTheFaultLeaksNothing() throws Exception {
        awaitStatus(code -> code == 200);
        owner("REVOKE USAGE ON SCHEMA scheduler FROM ipaas_app");
        try {
            awaitStatus(code -> code == 503);

            HttpResponse<String> readiness = get("/actuator/health/readiness");
            assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(503);
            assertThat(readiness.body()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
            assertThat(readiness.body()).doesNotContainIgnoringCase("scheduler").doesNotContain("reason")
                    .doesNotContain("components").doesNotContain("details");
            // liveness is about the process, not the scheduler grant: still UP
            HttpResponse<String> liveness = get("/actuator/health/liveness");
            assertThat(liveness.statusCode()).as(liveness.body()).isEqualTo(200);
            assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
        } finally {
            owner("GRANT USAGE ON SCHEMA scheduler TO ipaas_app");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Polls the open health path (60 s budget) until the status code is accepted; returns the last answer. */
    private HttpResponse<String> awaitStatus(IntPredicate accepted) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        long deadline = System.nanoTime() + 60_000_000_000L;
        HttpResponse<String> last = client.send(request, HttpResponse.BodyHandlers.ofString());
        while (!accepted.test(last.statusCode()) && System.nanoTime() < deadline) {
            Thread.sleep(500);
            last = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        return last;
    }

    private static void owner(String sql) throws Exception {
        try (Connection connection = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The login joins ipaas_app between Flyway and the scheduler start (as a deployment does, row 278). */
    @TestConfiguration(proxyBeanMethods = false)
    static class GrantAppRoleWiring {

        @Bean
        Callback grantAppRoleFlywayCallback() {
            return new GrantAppRoleFlywayCallback(POSTGRES, login);
        }
    }

    /**
     * The anonymous body is the status only: Spring adds the names of the health groups to the root answer (no
     * components, no details), so that one key is tolerated - anything else fails the match (L23).
     */
    private static String statusOnly(String status) {
        return "\\{(\"groups\":\\[[^\\]]*],)?\"status\":\"" + status + "\"}";
    }
}
