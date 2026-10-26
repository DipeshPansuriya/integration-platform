package io.integrationplatform.e11;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.identity.KeycloakRealmContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * BS-06.4 reproduction (row 375, base suite run 8; the front spec's {@code exactBytesBody} shape) over the
 * booted app's own intake HTTP endpoint - the layer above the clean-room transaction proof in
 * {@code IntakeCommitContainerTest}. The QA run's failing POST padded the body in {@code orderId}, the flow's
 * business key: over the btree key bound that blew the transaction into the catch-all 503
 * INTAKE_UNAVAILABLE. Now the failure is a deliberate refusal (422 BUSINESS_KEY_TOO_LONG, nothing persisted)
 * and the strict-">" cap stays true for a well-keyed record: a body of exactly 1 MB with a short business key
 * still commits (the commit path proof for values just under every boundary).
 *
 * <pre>
 * Scenario: the BS-06.4 body (1 MB, padding in the business key) -> 422 BUSINESS_KEY_TOO_LONG, nothing persisted
 * Scenario: 1 MB with a short business key (padding in a payload field) -> 202, message and payload commit
 * </pre>
 *
 * <p>Wiring mirrors {@code DipeshExampleEndToEndContainerTest} minus Kafka and the targets: the outbox relay is
 * conditional on the Kafka bootstrap servers, so intake alone runs end to end here (no relay, no WireMock).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("BS-06.4 reproduction: the exactly-1 MB intake POST over the app's own endpoint")
class IntakePayloadBoundaryReproContainerTest {

    private static final int PAYLOAD_LIMIT = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final String UUID_REGEX =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    /** Only source systems call in here (client credentials); no console login, so the redirects stay inert. */
    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            "http://localhost/login/oauth2/code/keycloak", "http://localhost/");

    private static PlatformTestWiring.RestrictedLogin login;
    private static KeycloakRealmContainer.ClientCredentials source;
    private static UUID tenantAId;
    private static ApplicationContext appContext;
    private static URI backend;
    private static long startedAtOnboarding;

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start(); // idempotent; the issuer must be known before the context starts
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_URL", () -> "/");
        // routing's operator settings; with no Kafka the relay never runs, so no target is ever dialled
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.e11.test");
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    @BeforeEach
    void onboardWhileRunningThenPostOnce() throws Exception {
        if (backend != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        backend = URI.create("http://localhost:" + port);
        appContext = context;
        startedAtOnboarding = context.getStartupDate();
        source = KEYCLOAK.createConfidentialClient("bs064-src-" + UUID.randomUUID().toString().substring(0, 8),
                true);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenantAId = DipeshExampleSeed.onboard(owner, "alpha", "CUST-A", source.clientId(),
                    KEYCLOAK.issuerUri()).id();
        }
    }

    @Test
    @DisplayName("BS-06.4's body (1 MB, padding in the business key) is refused 422 with the cap named, never 503")
    void exactlyOneMegabyteBodyWithBusinessKeyPaddingIsRefusedNot503() throws Exception {
        // the exactBytesBody shape: the record's orderId carries the padding, so the business key itself is ~1 MB
        String prefix = "{\"customerId\":\"CUST-A\",\"orderId\":\"O-BS064";
        String body = prefix + "x".repeat(PAYLOAD_LIMIT - prefix.length() - "\",\"amount\":1}".length())
                + "\",\"amount\":1}";
        assertThat(body).as("the BS-06.4 body shape").hasSize(PAYLOAD_LIMIT);

        HttpResponse<String> answer = post(source, body);

        assertThat(answer.statusCode())
                .as("BS-06.4: the deliberate refusal, never the catch-all 503: %s", answer.body())
                .isEqualTo(422);
        assertThat(JSON.readTree(answer.body()).path("code").asString()).isEqualTo("BUSINESS_KEY_TOO_LONG");
        assertThat(JSON.readTree(answer.body()).path("trackingId").asString()).isEmpty();
        assertThat(JSON.readTree(answer.body()).path("message").asString()).contains("2048");
        assertThat(context.getStartupDate()).as("no restart").isEqualTo(startedAtOnboarding);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(count(owner, "select count(*) from public.message", "tenant_id", tenantAId))
                    .as("nothing persisted for the over-bound key").isEqualTo(0);
        }
    }

    @Test
    @DisplayName("BS-06.4's boundary kept: 1 MB with a short business key is accepted 202 and commits the payload")
    void exactlyOneMegabyteBodyWithAShortBusinessKeyCommits() throws Exception {
        String prefix = "{\"customerId\":\"CUST-A\",\"orderId\":\"ORD-A-1\",\"note\":\"";
        String body = prefix + "n".repeat(PAYLOAD_LIMIT - prefix.length() - "\",\"amount\":1}".length())
                + "\",\"amount\":1}";
        assertThat(body).as("the just-under-boundary commit path").hasSize(PAYLOAD_LIMIT);

        HttpResponse<String> answer = post(source, body);

        assertThat(answer.statusCode()).as("the strict > cap accepts: %s", answer.body()).isEqualTo(202);
        String tracking = JSON.readTree(answer.body()).path("trackingId").asString();
        assertThat(tracking).matches(UUID_REGEX);
        assertThat(appContext.getStartupDate()).as("no restart").isEqualTo(startedAtOnboarding);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(count(owner, "select count(*) from public.message", "tenant_id", tenantAId))
                    .as("the 1 MB record is committed").isEqualTo(1);
            assertThat(count(owner, "select count(*) from public.payload p join public.message m"
                    + " on m.id = p.message_id and m.tenant_id = p.tenant_id", "p.tenant_id", tenantAId))
                    .as("the payload family row count").isEqualTo(1);
        }
    }

    /** A source POST to the one common intake, exactly as the base suite's rawIntake sends it. */
    private static HttpResponse<String> post(KeycloakRealmContainer.ClientCredentials caller, String body)
            throws Exception {
        String token = KEYCLOAK.clientCredentialsToken(caller.clientId(), caller.secret());
        return HTTP.send(HttpRequest.newBuilder(backend.resolve(
                        "/api/v1/interfaces/" + DipeshExampleSeed.INTERFACE_CODE + "/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", DipeshExampleSeed.SOURCE_CONTRACT_LABEL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A count where the seeded tenant is the predicate (the seed's codes are generated `tnt-e11-…` values). */
    private static int count(Connection sql, String statement, String tenantColumn, UUID tenantId) throws Exception {
        try (PreparedStatement query = sql.prepareStatement(statement + " where " + tenantColumn + " = ?::uuid")) {
            query.setObject(1, tenantId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}