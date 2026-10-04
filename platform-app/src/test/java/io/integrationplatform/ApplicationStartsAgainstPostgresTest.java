package io.integrationplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenario "The skeleton starts against PostgreSQL" (E0-F3-S1): Java 25 + Spring Boot 4.1.1 + Camel 4.22.1 start
 * together against a real PostgreSQL. Container group (R03): runs only with ./mvnw -Pcontainer, one suite at a time (R06).
 *
 * <p>E1 (T-002): wired through the fixed environment names only ({@link PlatformTestWiring}) - Flyway as the container's
 * owner, the app pool as a restricted LOGIN user with a random password, test OIDC issuer and audience. No
 * {@code @ServiceConnection}: it would replace the restricted username with the owner's. No literal password (R07).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApplicationStartsAgainstPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static PlatformTestWiring.RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private DataSource dataSource;

    @Test
    void healthEndpointReportsUp() throws Exception { // no token: health stays open (lane B chain)
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void camelContextIsStarted() {
        assertThat(camelContext.isStarted()).isTrue();
        assertThat(camelContext.getVersion()).isEqualTo("4.22.1");
    }

    @Test
    void theAppPoolConnectsAsTheRestrictedLoginNotTheOwner() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT current_user, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(login.username()).isNotEqualTo(POSTGRES.getUsername());
            assertThat(rs.getBoolean(2)).as("superuser").isFalse();
            assertThat(rs.getBoolean(3)).as("bypassrls").isFalse();
        }
    }
}
