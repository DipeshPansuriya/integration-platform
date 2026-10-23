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
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles({"showcase", "test"}) // showcase: the partner host names resolve to loopback on exact ports; test: the partner Postgres has no TLS (as E18Base)
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

    /**
     * L23: the fixture follows the role matrix. The job admin routes are OPERATOR_AGENT-only (JobEndpointRoles; plan
     * S4-A5 (iv): a tenant admin holds no job operation in R1), so those calls carry an operator-agent token (an
     * (issuer, azp) registered in scheduler.operator_credential, no human binding); every other call carries the
     * seeded tenant admin's token.
     */
    private static final String JOBS_PREFIX = "/api/v1/admin/jobs";
    private static final String AGENT_CLIENT = "t094-operator-agent";
    /** Seeded by migration V2_2_1420 (scheduler.job_definition). */
    private static final String JOB_TYPE = "DELIVERY_RETRY";
    private static final String DAY = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
    private static final UUID SYSTEM_ID = UUID.nameUUIDFromBytes("t094-system".getBytes(StandardCharsets.UTF_8));
    private static final UUID PENDING_ROW_ID = UUID.nameUUIDFromBytes("t094-pending".getBytes(StandardCharsets.UTF_8));
    private static final UUID APPROVED_ROW_ID =
            UUID.nameUUIDFromBytes("t094-approved".getBytes(StandardCharsets.UTF_8));
    private static final UUID ENDPOINT_ID = UUID.nameUUIDFromBytes("t094-endpoint".getBytes(StandardCharsets.UTF_8));
    private static final UUID TRACKING_ID = UUID.nameUUIDFromBytes("t094-message".getBytes(StandardCharsets.UTF_8));
    private static final String NARROW_ACCOUNT = "t094_narrow";
    private static final String NARROW_PASSWORD = "t094-" + UUID.randomUUID().toString().substring(0, 8);
    /** The partner DB system's settings: the suite's own Postgres, reached as the narrow account (set at seed time, once the port is mapped). */
    private static String dbSettings;
    private static final String KEK = java.util.Base64.getEncoder().encodeToString(
            new java.security.SecureRandom().generateSeed(32));

    /** The seeded app user's subject: the identity every exercised call is bound by (the credential carries the tenant). */
    private static String seededSubject;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        // L23 harness (the E18Base set, for the one partner DB): the approval probes the partner account with its vault password
        registry.add("PLATFORM_CREDENTIAL_KEK_K1", () -> KEK);
        registry.add("PLATFORM_CREDENTIAL_KEK_ACTIVE", () -> "K1");
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.t094.test");
        registry.add("platform.config.destinations.showcase-local-hosts", () -> "db.t094.test");
        registry.add("platform.config.destinations.showcase-local-ports", () -> String.valueOf(POSTGRES.getMappedPort(5432)));
        registry.add("platform.config.destinations.db-sslmode", () -> "disable");
        registry.add("platform.config.destinations.db-allowed-ports", () -> String.valueOf(POSTGRES.getMappedPort(5432)));
        registry.add("IPASS_DB_TARGET_ENABLED", () -> "true");
    }

    /** The suite's tokens are verified against the in-memory key; the configured issuer is never contacted. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder typedClientSuiteJwtDecoder() {
            return TOKENS.decoder();
        }

        /** {@code *.t094.test} resolves to loopback; the guard's showcase exception (exact host, exact port) decides whether that is allowed. */
        @Bean
        @Primary
        io.integrationplatform.config.destination.HostResolver partnerNames() {
            return host -> {
                if (host.endsWith(".t094.test")) {
                    return java.util.List.of(java.net.InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
                }
                throw new java.net.UnknownHostException(host);
            };
        }

        /** The login joins ipaas_app between Flyway and the scheduler start, as a deployment does. */
        @Bean
        org.flywaydb.core.api.callback.Callback grantAppRoleFlywayCallback() {
            return new io.integrationplatform.app.scheduler.GrantAppRoleFlywayCallback(POSTGRES, login);
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
            UUID userId = UUID.randomUUID();
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
                user.setString(1, userId.toString());
                user.setString(2, tenantId.toString());
                user.setString(3, PlatformTestWiring.OIDC_ISSUER.toString());
                user.setString(4, seededSubject);
                user.executeUpdate();
            }
            // E4 T-099 seeding: roles are role_grant rows; the typed client exercises every admin operation
            try (PreparedStatement grant = owner.prepareStatement(
                    "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')")) {
                grant.setString(1, tenantId.toString());
                grant.setString(2, userId.toString());
                grant.executeUpdate();
            }
            seedOperatorAgentAndRows(owner, tenantId);
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        storePartnerAccountPassword();
    }

    /** T178a: the approval reads the partner account's password from the vault - stored through the real credential API (TENANT_ADMIN). */
    private void storePartnerAccountPassword() {
        try {
            HttpResponse<String> stored = http("PUT", "http://localhost:" + port + "/api/v1/admin/target-endpoints/"
                    + ENDPOINT_ID + "/credentials", TOKENS.accessToken(seededSubject),
                    Optional.of("{\"fields\":{\"accountPassword\":\"" + NARROW_PASSWORD + "\"}}"));
            assertThat(stored.statusCode()).as("store the partner account password: %s", stored.body()).isEqualTo(200);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** L23: the operator-agent binding plus one DB system with a pending and an approved table, and one tracked message. */
    private static void seedOperatorAgentAndRows(Connection owner, UUID tenantId) throws SQLException {
        execute(owner, "INSERT INTO scheduler.operator_credential (issuer, client_id, status) VALUES (?, ?, 'ACTIVE')",
                PlatformTestWiring.OIDC_ISSUER.toString(), AGENT_CLIENT);
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction) "
                + "VALUES (?::uuid, ?::uuid, 't094-db', 't094 DB', 'TARGET')", SYSTEM_ID.toString(), tenantId.toString());
        dbSettings = "{\"host\":\"db.t094.test\",\"port\":" + POSTGRES.getMappedPort(5432) + ",\"database\":\""
                + POSTGRES.getDatabaseName() + "\",\"user\":\"" + NARROW_ACCOUNT + "\",\"table\":\"t094_pending\"}";
        execute(owner, "UPDATE public.system SET type = 'DB', settings = ?::jsonb WHERE id = ?::uuid",
                dbSettings, SYSTEM_ID.toString());
        // the partner's narrow account (T178a): INSERT and SELECT on the one requested table, nothing else
        execute(owner, "CREATE TABLE public.t094_pending (order_no text)");
        execute(owner, "CREATE ROLE " + NARROW_ACCOUNT + " LOGIN PASSWORD '" + NARROW_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        execute(owner, "REVOKE ALL ON public.t094_pending FROM " + NARROW_ACCOUNT);
        execute(owner, "GRANT INSERT, SELECT ON public.t094_pending TO " + NARROW_ACCOUNT);
        execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                + " http_method, auth_method, timeout_ms) VALUES (?::uuid, ?::uuid, ?::uuid, 't094-db-endpoint',"
                + " 't094 DB endpoint', 'jdbc:postgresql://db.t094.test/t094', 'POST', 'NO_AUTH', 5000)",
                ENDPOINT_ID.toString(), tenantId.toString(), SYSTEM_ID.toString());
        insertApprovedTable(owner, tenantId, PENDING_ROW_ID, "t094_pending", "PENDING_APPROVAL", null);
        insertApprovedTable(owner, tenantId, APPROVED_ROW_ID, "t094_approved", "APPROVED", "t094-seed-approver");
        execute(owner, "INSERT INTO public.message (id, tenant_id, interface_id, interface_version_id,"
                        + " source_contract_version_id, business_key, state, accepted_at)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 't094-message', 'ROUTE_FAILED', now())",
                TRACKING_ID.toString(), tenantId.toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        execute(owner, "INSERT INTO public.message_stage_timing (message_id, tenant_id, accepted_at, stage,"
                        + " started_at, finished_at) VALUES (?::uuid, ?::uuid, now(), 'INTAKE', now(), now())",
                TRACKING_ID.toString(), tenantId.toString());
    }

    private static void insertApprovedTable(Connection owner, UUID tenantId, UUID rowId, String table, String status,
            String decidedBy) throws SQLException {
        execute(owner, "INSERT INTO public.db_target_approved_table (id, tenant_id, system_id, table_name, status,"
                        + " requested_by, decided_by, decided_at, target_hash)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 't094-seed-requester', ?::text,"
                        + " CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, public.db_target_fingerprint(?::jsonb))",
                rowId.toString(), tenantId.toString(), SYSTEM_ID.toString(), table, status, decidedBy, decidedBy,
                dbSettings);
    }

    private static void execute(Connection owner, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
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
        if (method.equals("post") && path.endsWith("/approved-tables")) {
            body = Optional.of("{\"table\":\"t094_requested\"}"); // a plain identifier (the generic sample has a hyphen)
        }
        String bearer = path.startsWith(JOBS_PREFIX)
                ? TOKENS.accessToken("sub-t094-agent", AGENT_CLIENT)
                : TOKENS.accessToken(seededSubject);
        HttpResponse<String> response =
                request(method.toUpperCase(), path + requiredQuery(operation), base, bearer, body);

        // The answered status must be covered by a response the client declares (a 2XX-styled range counts).
        Set<String> declaredStatuses = fieldNames(operation.path("responses"));
        if (!statusDeclared(response.statusCode(), declaredStatuses)) {
            mismatches.add(method.toUpperCase() + " " + path + " answered " + response.statusCode()
                    + " - no declared response covers it (declared: " + declaredStatuses + "); body: "
                    + response.body().substring(0, Math.min(300, response.body().length())));
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
        return http(method, base + substituted(declaredPath), bearer, body);
    }

    /** Each path parameter takes the seeded row of its kind (L23); anything unlisted keeps the fixed sample id. */
    private static String substituted(String declaredPath) {
        String path = declaredPath.replace("{jobType}", JOB_TYPE).replace("{systemId}", SYSTEM_ID.toString())
                .replace("{trackingId}", TRACKING_ID.toString());
        path = path.replace("{id}", (declaredPath.endsWith("/approve") ? PENDING_ROW_ID : APPROVED_ROW_ID).toString());
        return PATH_TEMPLATE.matcher(path).replaceAll(PATH_ID);
    }

    /** The query parameters the client declares as required, with a valid sample (only {@code day} exists today). */
    private static String requiredQuery(JsonNode operation) {
        StringBuilder query = new StringBuilder();
        for (JsonNode parameter : operation.path("parameters")) {
            if ("query".equals(parameter.path("in").asString("")) && parameter.path("required").asBoolean(false)) {
                String name = parameter.path("name").asString("");
                query.append(query.length() == 0 ? '?' : '&').append(name).append('=')
                        .append(name.equals("day") ? DAY : SAMPLE);
            }
        }
        return query.toString();
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