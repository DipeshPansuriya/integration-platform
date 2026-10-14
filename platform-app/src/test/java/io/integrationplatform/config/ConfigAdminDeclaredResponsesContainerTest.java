package io.integrationplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E3-F3-S1 (plan C9), the server half of "the typed client finds 0 mismatches": the OpenAPI document the running
 * application publishes declares, per config admin operation, exactly the statuses the controller and the config
 * error seam really answer - so the generated client is not left declaring only 200.
 *
 * <pre>
 * Scenario: The config admin API declares every status it answers
 *   Given the platform is running
 *   When the OpenAPI document is read
 *   Then POST /api/v1/admin/flows declares 201, 400, 403 and 409 with the error body schema
 *   And GET /api/v1/admin/flows/{flowId} declares 200, 403 and 404
 *   And PUT /api/v1/admin/flows/{flowId}/versions/{versionId} declares 403, 404 and 409 (never a 2xx)
 *   And every other admin operation declares its own real set (table below)
 * </pre>
 *
 * <p>The table is derived from the code, not from the document: every operation answers 403 NO_BOUND_USER through
 * {@code ConfigActors.requireActor}; 400 is each service's {@code ConfigApiException.validation}; 404 is a row the
 * bound tenant cannot see; 409 is {@code ConfigApiException.conflict}, the approve outcome NOT_PENDING_APPROVAL, or
 * the error seam's {@code DuplicateKeyException} answer (a write that hits a unique constraint - systems code,
 * contract version label, the destination onboarding's system code). The catch-all 500 and the security chain's
 * 401 are not part of the declared set.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ConfigAdminDeclaredResponsesContainerTest.SuiteDecoder.class)
class ConfigAdminDeclaredResponsesContainerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ERROR_SCHEMA = "#/components/schemas/ConfigErrorBody";
    private static final AtomicInteger SEEDED = new AtomicInteger();

    /** Every config admin operation and the statuses it really answers. */
    private static final Map<String, Set<String>> EXPECTED = Map.ofEntries(
            Map.entry("post /api/v1/admin/systems", Set.of("201", "400", "403", "409")),
            Map.entry("post /api/v1/admin/contracts/versions", Set.of("200", "400", "403", "409")),
            Map.entry("get /api/v1/admin/contracts/versions/{versionId}", Set.of("200", "403", "404")),
            Map.entry("post /api/v1/admin/contracts/versions/{versionId}/submit",
                    Set.of("200", "400", "403", "404", "409")),
            Map.entry("get /api/v1/admin/flows", Set.of("200", "403")),
            Map.entry("post /api/v1/admin/flows", Set.of("201", "400", "403", "409")),
            Map.entry("get /api/v1/admin/flows/{flowId}", Set.of("200", "403", "404")),
            Map.entry("put /api/v1/admin/flows/{flowId}", Set.of("200", "400", "403", "404", "409")),
            Map.entry("put /api/v1/admin/flows/{flowId}/versions/{versionId}", Set.of("403", "404", "409")),
            Map.entry("post /api/v1/admin/flows/{flowId}/submit", Set.of("200", "400", "403", "404", "409")),
            Map.entry("post /api/v1/admin/flows/{flowId}/approve", Set.of("200", "403", "404", "409")),
            Map.entry("post /api/v1/admin/flows/{flowId}/publish", Set.of("200", "403", "404", "409")),
            Map.entry("get /api/v1/admin/routes/{routeId}/mapping", Set.of("200", "403", "404")),
            Map.entry("put /api/v1/admin/routes/{routeId}/mapping", Set.of("200", "400", "403", "404", "409")));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static RestrictedLogin login;
    private static String seededSubject;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The document is read with a bearer; this suite's decoder trusts the token text as the subject. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder declaredResponsesSuiteJwtDecoder() {
            return token -> {
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .issuer(PlatformTestWiring.OIDC_ISSUER.toString())
                        .subject(token)
                        .audience(List.of(PlatformTestWiring.OIDC_AUDIENCE))
                        .issuedAt(now)
                        .expiresAt(now.plus(Duration.ofMinutes(5)))
                        .build();
            };
        }
    }

    /** Same instance-phase seeding as the T094 suite: the app's migrations have run by now. */
    @BeforeEach
    void seedOneTenantUser() throws SQLException {
        if (SEEDED.incrementAndGet() != 1) {
            return;
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            UUID tenantId = UUID.randomUUID();
            seededSubject = "sub-declared-" + UUID.randomUUID();
            try (PreparedStatement tenant = owner.prepareStatement(
                    "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
                tenant.setString(1, tenantId.toString());
                tenant.setString(2, "tnt-decl-" + Long.toUnsignedString(
                        Math.abs(UUID.randomUUID().getLeastSignificantBits()), 36));
                tenant.executeUpdate();
            }
            try (PreparedStatement user = owner.prepareStatement(
                    "INSERT INTO app_user (id, tenant_id, issuer, subject, status) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
                user.setString(1, UUID.randomUUID().toString());
                user.setString(2, tenantId.toString());
                user.setString(3, PlatformTestWiring.OIDC_ISSUER.toString());
                user.setString(4, seededSubject);
                user.executeUpdate();
            }
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
    }

    @Test
    @DisplayName("Scenario: The config admin API declares every status it answers")
    void everyConfigAdminOperationDeclaresItsRealStatuses() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + seededSubject)
                .GET()
                .build();
        HttpResponse<String> answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("GET /v3/api-docs").isEqualTo(200);
        JsonNode document = JSON.readTree(answer.body());

        List<String> mismatches = new ArrayList<>();
        EXPECTED.forEach((operation, expected) -> {
            String[] methodAndPath = operation.split(" ", 2);
            JsonNode responses = document.path("paths").path(methodAndPath[1]).path(methodAndPath[0])
                    .path("responses");
            Set<String> declared = new TreeSet<>(responses.propertyNames());
            if (!declared.equals(new TreeSet<>(expected))) {
                mismatches.add(operation + " declares " + declared + ", really answers " + new TreeSet<>(expected));
            }
            for (String status : expected) {
                JsonNode content = responses.path(status).path("content");
                if (status.startsWith("2")) {
                    if (!content.isObject() || content.isEmpty()) {
                        mismatches.add(operation + " " + status + " declares no success body schema");
                    }
                    continue;
                }
                boolean errorSchema = false;
                for (JsonNode media : content) {
                    errorSchema |= ERROR_SCHEMA.equals(media.path("schema").path("$ref").asString(""));
                }
                if (!errorSchema) {
                    mismatches.add(operation + " " + status + " does not reference " + ERROR_SCHEMA);
                }
            }
        });

        JsonNode errorBody = document.path("components").path("schemas").path("ConfigErrorBody").path("properties");
        if (!errorBody.has("error") || !errorBody.has("refusal")) {
            mismatches.add("ConfigErrorBody does not document both refusal body keys (error, refusal): " + errorBody);
        }
        assertThat(mismatches)
                .as("declared vs real statuses over the %d config admin operations", EXPECTED.size())
                .isEmpty();
    }
}
