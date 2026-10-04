package io.integrationplatform.tenantdenial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.Seed;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * E1-F1-S1 "A forged tenant changes nothing" - the forged-tenant denial suite, architecture.md section 5 control T1,
 * the E1 release gate (re-run by E4 and E15). Traces E1-C1, E1-C2, E1-C5.
 *
 * <p>The whole application runs (every module's endpoints) against PostgreSQL with the restricted app login. Every
 * {@code /api/**} endpoint has a matrix entry ({@link EndpointMatrix}); each gets the forgeries that fit it. A user of
 * tenant A signs in with a real signed bearer token; tenant B is seeded beside it and is what every forgery names.
 *
 * <pre>
 * Scenario: A tenant-A user sees only tenant-A rows
 *   Given rows for tenant A and tenant B
 *   And a signed-in user bound to tenant A
 *   When the user calls any read endpoint
 *   Then only tenant-A rows are returned
 *
 * &#64;negative &#64;tenant
 * Scenario Outline: A forged tenant is ignored
 *   Given a signed-in user bound to tenant A
 *   When the request carries &lt;forgery&gt; naming tenant B
 *   Then every response contains only tenant-A rows, or 404 for a tenant-B id
 *   And the forgery is recorded as a denied attempt
 *   Examples:
 *     | forgery                         |
 *     | a "Tenant" header               |
 *     | a tenantId field in the body    |
 *     | a tenantId query parameter      |
 *     | a tenant-B record id in the path |
 *
 * &#64;negative &#64;security
 * Scenario: No identity, no tenant
 *   Given an unauthenticated request that carries a tenant field
 *   When it is handled
 *   Then it is rejected with 401 and no tenant data is read
 * </pre>
 *
 * <p>Container group (R03), one Docker suite at a time (R06). It moves to platform-e2e when that module exists.
 */
@Tag("container")
@Tag("tenant")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestProbeController.class, ProbeRowReader.class})
@DisplayName("E1-F1-S1 A forged tenant changes nothing (release gate)")
class ForgedTenantDenialSuiteTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final TestTokens TOKENS = new TestTokens();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static RestrictedLogin login;
    private static Seed seed;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The suite's tokens are verified against the in-memory key; the configured issuer is never contacted. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteTokenDecoder {

        @Bean
        @Primary
        JwtDecoder tenantDenialSuiteJwtDecoder() {
            return TOKENS.decoder();
        }
    }

    @MockitoSpyBean
    private TenantDenialRecorder recorder;

    @Autowired
    private ProbeRowReader probe;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedOnceThenReset() throws Exception {
        if (seed == null) {
            seed = seedTenantsAandB();
        }
        clearInvocations(recorder);
        probe.resetReads();
    }

    static Stream<Entry> endpoints() {
        return EndpointMatrix.REGISTRY.stream();
    }

    static Stream<Object[]> forgeries() {
        return EndpointMatrix.forgeries();
    }

    static Stream<Object[]> tenantFields() {
        return EndpointMatrix.tenantFields();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("Scenario: A tenant-A user sees only tenant-A rows")
    void aTenantAUserSeesOnlyTenantARows(Entry entry) throws Exception {
        HttpResponse<String> response = send(entry, null, signedInAsTenantA());

        assertThat(response.statusCode()).as(entry + " status, body: " + response.body()).isEqualTo(200);
        assertOnlyTenantAData(entry, response);
        verify(recorder, never()).record(any());
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("forgeries")
    @Tag("negative")
    @DisplayName("Scenario Outline: A forged tenant is ignored")
    void aForgedTenantIsIgnored(Entry entry, Forgery forgery) throws Exception {
        HttpResponse<String> response = send(entry, forgery, signedInAsTenantA());

        if (forgery == Forgery.PATH_ID) {
            assertThat(response.statusCode()).as(entry + " with a tenant-B id, body: " + response.body()).isEqualTo(404);
            assertNoMarkerOf(seed.b(), response);
        } else {
            assertThat(response.statusCode()).as(entry + " with " + forgery + ", body: " + response.body()).isEqualTo(200);
            assertOnlyTenantAData(entry, response);
        }
        DeniedTenantAttempt attempt = theOneRecordedAttempt();
        assertThat(attempt.channel()).isEqualTo(forgery.channel());
        assertThat(attempt.boundTenantId()).isEqualTo(seed.a().id());
        assertThat(attempt.method()).isEqualTo(entry.method());
        assertThat(attempt.path()).isEqualTo(response.request().uri().getRawPath());
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("tenantFields")
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: No identity, no tenant")
    void noIdentityNoTenant(Entry entry, Forgery tenantField) throws Exception {
        HttpResponse<String> response = send(entry, tenantField, null);

        assertThat(response.statusCode()).as(entry + " without identity, body: " + response.body()).isEqualTo(401);
        assertNoMarkerOf(seed.a(), response);
        assertNoMarkerOf(seed.b(), response);
        assertThat(probe.reads()).as("tenant data reads").isZero();
        DeniedTenantAttempt attempt = theOneRecordedAttempt();
        assertThat(attempt.channel()).isEqualTo(DeniedTenantAttempt.Channel.NO_IDENTITY);
        assertThat(attempt.boundTenantId()).isNull();
        assertThat(attempt.method()).isEqualTo(entry.method());
        assertThat(attempt.path()).isEqualTo(response.request().uri().getRawPath());
    }

    @Test
    @DisplayName("Coverage guard: every /api/** endpoint of the running application has a matrix entry")
    void everyApiEndpointHasAMatrixEntry() {
        assertThat(EndpointMatrix.coverageProblems(handlerMapping.getHandlerMethods(), EndpointMatrix.REGISTRY))
                .isEmpty();
    }

    // --- requests -----------------------------------------------------------------------------------------------

    private String signedInAsTenantA() {
        return TOKENS.accessToken(seed.a().subject());
    }

    /** Builds the entry's tenant-A call, then applies the forgery naming tenant B (null = no forgery). */
    private HttpResponse<String> send(Entry entry, Forgery forgery, String bearer) throws Exception {
        String foreignTenant = seed.b().id().toString();
        String path = forgery == Forgery.PATH_ID ? entry.foreignIdPath().apply(seed) : entry.pathForA().apply(seed);
        if (forgery == Forgery.QUERY_PARAMETER) {
            path += (path.contains("?") ? "&" : "?") + Forgery.TENANT_FIELD + "="
                    + URLEncoder.encode(foreignTenant, StandardCharsets.UTF_8);
        }
        String body = entry.acceptsBody() ? entry.bodyForA().apply(seed) : null;
        if (forgery == Forgery.BODY_FIELD) {
            ObjectNode forged = (ObjectNode) JSON.readTree(body);
            forged.put(Forgery.TENANT_FIELD, foreignTenant);
            body = JSON.writeValueAsString(forged);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (forgery == Forgery.HEADER) {
            request.header(Forgery.TENANT_HEADER, foreignTenant);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(entry.method(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // --- assertions ---------------------------------------------------------------------------------------------

    private void assertOnlyTenantAData(Entry entry, HttpResponse<String> response) {
        assertNoMarkerOf(seed.b(), response);
        assertThat(response.body()).as(entry + " answers with the tenant-A data")
                .contains(entry.tenantAData().apply(seed));
        JsonNode json = JSON.readTree(response.body());
        for (JsonNode tenantId : json.findValues(Forgery.TENANT_FIELD)) {
            assertThat(tenantId.asString()).as(entry + " tenantId in the answer").isEqualTo(seed.a().id().toString());
        }
    }

    private static void assertNoMarkerOf(SeededTenant tenant, HttpResponse<String> response) {
        for (String marker : tenant.markers()) {
            assertThat(response.body()).as("answer leaks a value of tenant " + tenant.code()).doesNotContain(marker);
        }
    }

    private DeniedTenantAttempt theOneRecordedAttempt() {
        ArgumentCaptor<DeniedTenantAttempt> attempt = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder, times(1)).record(attempt.capture());
        return attempt.getValue();
    }

    // --- seed (as the Flyway owner; the app login never writes the directory) ------------------------------------

    private static Seed seedTenantsAandB() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/fixture/lane-d")
                .table("flyway_fixture_lane_d_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);

        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            SeededTenant a = seedTenant(owner, "alpha");
            SeededTenant b = seedTenant(owner, "bravo");
            try (PreparedStatement count = owner.prepareStatement("SELECT count(*) FROM probe_row WHERE tenant_id = ?::uuid")) {
                count.setString(1, b.id().toString());
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("tenant-B rows exist, so 'only tenant A' is not vacuous").isEqualTo(2);
                }
            }
            return new Seed(a, b);
        }
    }

    private static SeededTenant seedTenant(Connection owner, String word) throws Exception {
        UUID tenantId = UUID.randomUUID();
        String code = "tnt-" + word + "-" + tenantId.toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        String subject = "sub-" + word + "-" + UUID.randomUUID();
        try (PreparedStatement tenant = owner.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            tenant.setString(1, tenantId.toString());
            tenant.setString(2, code);
            tenant.executeUpdate();
        }
        try (PreparedStatement user = owner.prepareStatement(
                "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
            user.setString(1, userId.toString());
            user.setString(2, tenantId.toString());
            user.setString(3, PlatformTestWiring.OIDC_ISSUER.toString());
            user.setString(4, subject);
            user.executeUpdate();
        }
        List<UUID> rowIds = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            UUID rowId = UUID.randomUUID();
            String label = word + "-" + i;
            try (PreparedStatement row = owner.prepareStatement(
                    "INSERT INTO probe_row (id, tenant_id, label) VALUES (?::uuid, ?::uuid, ?)")) {
                row.setString(1, rowId.toString());
                row.setString(2, tenantId.toString());
                row.setString(3, label);
                row.executeUpdate();
            }
            rowIds.add(rowId);
            labels.add(label);
        }
        return new SeededTenant(tenantId, code, userId, subject, List.copyOf(rowIds), List.copyOf(labels));
    }
}
