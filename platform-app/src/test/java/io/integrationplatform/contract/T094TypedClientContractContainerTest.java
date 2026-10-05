package io.integrationplatform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-094 (E3-F3-S1): the typed-client contract test - the E3-C9 proof, "@negative: the typed client finds 0
 * mismatches".
 *
 * <pre>
 * &#64;negative
 * Scenario: The typed client finds 0 mismatches
 *   Given the console's typed client and the real controllers
 *   When the contract test runs every call
 *   Then it reports 0 mismatches, and any added mismatch fails the build
 * </pre>
 *
 * <p>Mechanism (lane A's decision, reversible - reported back through arjun-fullstack): the whole application
 * runs against PostgreSQL (container group R03, one Docker suite at a time R06); the test fetches the springdoc
 * document ({@code GET /v3/api-docs}, armed by the lead's springdoc seam) and exercises every operation the
 * console's typed client declares - read from ui/'s checked-in generated client contract (lane D owns that
 * tree; this suite only reads it) - against the real controllers with real HTTP, counting every mismatch: an
 * operation missing from the running API, an answered status no declared response covers, a 2xx body missing a
 * required field. Zero is the assertion, so any mismatch the client or the API adds fails the build.
 *
 * <p>State today (written test-first, the committed RED): {@code ui/} does not exist yet (the console scaffold
 * T-158 is lane D's), so the suite fails at the named gate first; the mechanism itself is real from the first
 * run - nothing is stubbed to green.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(T094TypedClientContractContainerTest.SuiteDecoder.class)
class T094TypedClientContractContainerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;
    private static final Pattern PATH_TEMPLATE = Pattern.compile("\\{[^/}]+}");
    private static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch");
    private static final String SAMPLE = "t094-sample";
    private static final String PATH_ID =
            UUID.nameUUIDFromBytes("t094".getBytes(StandardCharsets.UTF_8)).toString();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final ContractTestTokens TOKENS = new ContractTestTokens();
    private static final AtomicInteger SEEDED = new AtomicInteger();
    private static RestrictedLogin login;

    /** The seeded app user's subject: the identity every exercised call is bound by (the credential carries the tenant). */
    private static String seededSubject;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The suite's tokens are verified against the in-memory key; the configured issuer is never contacted. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder typedClientSuiteJwtDecoder() {
            return TOKENS.decoder();
        }
    }

    /**
     * Seeds once, in the instance phase the {@code ForgedTenantDenialSuiteTest} pattern uses: by the time a
     * (non-static) {@code @BeforeEach} runs, the composed context has booted and the application's own
     * migrations have applied, so {@code tenant} exists (a static {@code @BeforeAll} would race the boot).
     */
    @BeforeEach
    void seedTheTenantAfterTheApplicationMigratedTheSchema() throws SQLException {
        if (SEEDED.incrementAndGet() != 1) {
            return;
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            UUID tenantId = UUID.randomUUID();
            seededSubject = "sub-t094-" + UUID.randomUUID();
            try (PreparedStatement tenant = owner.prepareStatement(
                    "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
                tenant.setString(1, tenantId.toString());
                tenant.setString(2, "tnt-t094-" + Long.toUnsignedString(
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

    // ------------------------------------------------------------------ the scenario

    @LocalServerPort
    private int port;

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: The typed client finds 0 mismatches against the real controllers")
    void theTypedClientFindsZeroMismatches() throws IOException, InterruptedException {
        // The springdoc document of the running application (the lead's seam exposes it), fetched as the
        // typed client fetches it: with the console's credential on the call.
        String base = "http://localhost:" + port;
        HttpResponse<String> apiDoc = http("GET", base + "/v3/api-docs", TOKENS.accessToken(seededSubject), null);
        if (apiDoc.statusCode() != 200) {
            pendingIntegration("GET /v3/api-docs answered " + apiDoc.statusCode()
                    + " - the OpenAPI document the typed client is generated against is not published",
                    "arjun-fullstack");
        }
        JsonNode apiPaths = JSON.readTree(apiDoc.body()).path("paths");
        assertThat(apiPaths.isObject()).as("the api-docs document of the running application").isTrue();

        // The console's typed client: ui/'s checked-in generated contract (lane D owns; read only).
        Path clientDocument = checkedInClientDocument();
        JsonNode client = JSON.readTree(Files.readString(clientDocument));

        List<String> mismatches = new ArrayList<>();
        int exercised = 0;
        JsonNode clientPaths = client.path("paths");
        Set<String> declaredPathNames = clientPaths instanceof ObjectNode objectNode
                ? new LinkedHashSet<>(objectNode.propertyNames())
                : Set.of();
        var paths = declaredPathNames.iterator();
        while (paths.hasNext()) {
            String declaredPath = paths.next();
            JsonNode declaredOperations = clientPaths.path(declaredPath);
            for (String method : METHODS) {
                if (!declaredOperations.has(method)) {
                    continue;
                }
                JsonNode apiOperation = apiPaths.path(declaredPath).path(method);
                if (apiOperation.isMissingNode()) {
                    mismatches.add("the client declares " + method.toUpperCase() + " " + declaredPath
                            + " - the running API does not offer it");
                    continue;
                }
                exercised++;
                exercise(base, declaredPath, method, declaredOperations.path(method), client, mismatches);
            }
        }

        assertThat(mismatches)
                .as("the typed client vs the real controllers: %d client operations exercised, none may mismatch "
                        + "(client doc: %s)", exercised, clientDocument.getFileName())
                .isEmpty();
    }

    // ------------------------------------------------------------------ the exercise of one operation

    private static void exercise(String base, String path, String method, JsonNode operation, JsonNode clientDoc,
            List<String> mismatches) throws IOException, InterruptedException {
        Optional<String> body = requestBodyFor(clientDoc, operation);
        HttpResponse<String> response =
                request(method.toUpperCase(), path, base, TOKENS.accessToken(seededSubject), body);

        // The answered status must be covered by a response the client declares (a 2XX-styled range counts).
        Set<String> declaredStatuses = fieldNames(operation.path("responses"));
        if (!statusDeclared(response.statusCode(), declaredStatuses)) {
            mismatches.add(method.toUpperCase() + " " + path + " answered " + response.statusCode()
                    + " - no declared response covers it (declared: " + declaredStatuses + ")");
        }

        // A 2xx response must satisfy the schema the client declares for that response (required fields present).
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonNode responseSchema = schemaOfResponse(clientDoc, operation.path("responses"), response.statusCode());
            if (!responseSchema.isMissingNode()) {
                List<String> missing =
                        requiredFieldsMissing(resolve(clientDoc, responseSchema), readBody(response));
                if (!missing.isEmpty()) {
                    mismatches.add(method.toUpperCase() + " " + path + " answered " + response.statusCode()
                            + " missing required field " + missing);
                }
            }
        } else if (body.isEmpty() && !operation.path("requestBody").isMissingNode()
                && !declaredStatuses.contains("400") && !declaredStatuses.contains("204")) {
            // The operation requires a body the minimal generator could not build (a schema richer than the
            // faithful minimum, or its contract changed): a named mismatch for the owner, never silent.
            mismatches.add(method.toUpperCase() + " " + path
                    + " needs a body the minimal generator could not build from the client's schema");
        }
    }

    private static JsonNode readBody(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException e) {
            return FACTORY.objectNode();
        }
    }

    private static boolean statusDeclared(int status, Set<String> declaredStatuses) {
        if (declaredStatuses.contains(String.valueOf(status))) {
            return true;
        }
        return declaredStatuses.stream().anyMatch(declared -> {
            if (declared.length() != 3 || declared.endsWith("X")) {
                return false;
            }
            int firstDigit = Character.getNumericValue(declared.charAt(0));
            return status >= firstDigit * 100 && status <= firstDigit * 100 + 99;
        });
    }

    private static JsonNode schemaOfResponse(JsonNode clientDoc, JsonNode declaredResponses, int status) {
        JsonNode declared = declaredResponses.path(String.valueOf(status));
        if (declared.isMissingNode()) {
            for (String name : fieldNames(declaredResponses)) {
                if (name.length() == 3 && name.endsWith("X")
                        && Character.getNumericValue(name.charAt(0)) == status / 100) {
                    declared = declaredResponses.path(name);
                    break;
                }
            }
        }
        return declared.path("content").path("application/json").path("schema");
    }

    private static List<String> requiredFieldsMissing(JsonNode schema, JsonNode body) {
        List<String> missing = new ArrayList<>();
        for (String required : fieldNames(schema.path("required"))) {
            if (!body.has(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    private static Set<String> fieldNames(JsonNode object) {
        return new LinkedHashSet<>(object instanceof ObjectNode objectNode
                ? objectNode.propertyNames()
                : java.util.List.of());
    }

    // ------------------------------------------------------------------ request generation from the client-declared schema

    /** A minimal body conforming to the schema the client declared (required fields, by construction). */
    private static Optional<String> requestBodyFor(JsonNode clientDoc, JsonNode operation) {
        JsonNode requestBody = operation.path("requestBody");
        if (requestBody.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode schema = requestBody.path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) {
            return Optional.empty();
        }
        return generatedBody(clientDoc, resolve(clientDoc, schema), 0);
    }

    private static Optional<String> generatedBody(JsonNode clientDoc, JsonNode schema, int depth) {
        if (depth > 4) {
            return Optional.empty();
        }
        ObjectNode object = FACTORY.objectNode();
        for (String required : fieldNames(schema.path("required"))) {
            JsonNode property = resolve(clientDoc, schema.path("properties").path(required));
            object.set(required, leafValue(clientDoc, property, depth + 1));
        }
        return Optional.of(JSON.writeValueAsString(object));
    }

    private static JsonNode leafValue(JsonNode clientDoc, JsonNode property, int depth) {
        JsonNode enumValues = property.path("enum");
        if (enumValues.isArray() && !enumValues.isEmpty()) {
            return enumValues.get(0);
        }
        String type = property.path("type").asString("string");
        return switch (type) {
            case "integer" -> FACTORY.numberNode(1);
            case "number" -> FACTORY.numberNode(1.0);
            case "boolean" -> FACTORY.booleanNode(true);
            case "array" -> FACTORY.arrayNode();
            case "object" -> nestedObject(clientDoc, property, depth);
            default -> FACTORY.textNode(SAMPLE);
        };
    }

    private static ObjectNode nestedObject(JsonNode clientDoc, JsonNode property, int depth) {
        ObjectNode nested = FACTORY.objectNode();
        if (depth > 4) {
            return nested;
        }
        for (String required : fieldNames(property.path("required"))) {
            nested.set(required, leafValue(clientDoc,
                    resolve(clientDoc, property.path("properties").path(required)), depth + 1));
        }
        return nested;
    }

    /** The $ref the client's document names, resolved inside it (a checked-in single-file contract). */
    private static JsonNode resolve(JsonNode document, JsonNode schema) {
        String pointer = schema.asString("");
        if (pointer.startsWith("#/")) {
            JsonNode node = document;
            for (String segment : pointer.substring(2).split("/")) {
                node = node.path(segment.replace("~1", "/").replace("~0", "~"));
            }
            if (!node.isMissingNode()) {
                return node;
            }
        }
        return schema;
    }

    // ------------------------------------------------------------------ ui/'s checked-in generated contract (read-only)

    /** The openapi json document the generated client was checked in with, under ../ui/ (lane D's tree). */
    private static Path checkedInClientDocument() throws IOException {
        Path ui = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize()
                .getParent().resolve("ui");
        if (!Files.isDirectory(ui)) {
            pendingIntegration("no ui/ tree - the console does not exist yet", "lane D (T-158)");
        }
        try (Stream<Path> found = Files.walk(ui)) {
            List<Path> documents = found
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".json") && name.contains("openapi");
                    })
                    .toList();
            if (documents.isEmpty()) {
                pendingIntegration(
                        "ui/ holds no checked-in OpenAPI document the generated client was built from",
                        "lane D (T-158)");
            }
            return documents.get(0);
        }
    }

    private static <T> T pendingIntegration(String what, String owner) {
        throw new IllegalStateException("pending integration: " + what + " (owner: " + owner + ")");
    }

    // ------------------------------------------------------------------ real HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Builds and sends the entry's real call; the path's parameters are substituted with a fixed sample id. */
    private static HttpResponse<String> request(String method, String declaredPath, String base, String bearer,
            Optional<String> body) throws IOException, InterruptedException {
        return http(method, base + PATH_TEMPLATE.matcher(declaredPath).replaceAll(PATH_ID), bearer, body);
    }

    private static HttpResponse<String> http(String method, String url, String bearer, Optional<String> body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        String content = body == null ? null : body.orElse(null);
        if (content != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, content == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}