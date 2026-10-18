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
import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import io.integrationplatform.tenancy.RolePolicy;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.integrationplatform.tenantdenial.EndpointMatrix.Config;
import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.Seed;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import io.integrationplatform.tenantdenial.TestTokens.ConsoleSession;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Import;
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
 * the E1 release gate - re-run after login by E4-F1-S4 (T-101, ADR-007 Decision 2 conformance, release gate G6) with
 * Keycloak-issued tokens only. Traces E1-C1, E1-C2, E1-C5, E4-C6, E4-C7.
 *
 * <p>The whole application runs (every module's endpoints) against PostgreSQL with the restricted app login and
 * lane D's pinned Keycloak realm ({@link KeycloakRealmContainer}). Every {@code /api/**} endpoint has a matrix entry
 * ({@link EndpointMatrix}); each gets the forgeries that fit it. Tenants A and B each have a Keycloak user bound
 * through an {@code app_user} row and holding TENANT_ADMIN (so every endpoint is reached: a denial proves tenant
 * isolation, never a missing role), a second user whose token claims the other tenant, and a source system whose
 * Keycloak client resolves through an ACTIVE {@code source_credential} row to the tenant and its customer identity.
 * Users call through the console's session (ADR-007 Decision 1), sources with their client-credentials bearer token.
 *
 * <pre>
 * Scenario: The cross-tenant denial suite re-passes with real tokens
 *   Given Keycloak-issued tokens for users and sources of tenants A and B
 *   When the E1 forged-tenant suite runs
 *   Then every case passes (0 rows of another tenant returned)
 *
 * Scenario: A tenant-A user sees only tenant-A rows        (and every user and source of A and B alike)
 *   Given rows for tenant A and tenant B
 *   And a signed-in user bound to tenant A
 *   When the user calls any read endpoint
 *   Then only tenant-A rows are returned
 *
 * &#64;negative &#64;tenant
 * Scenario Outline: A forged tenant is ignored                (users of A and B, each naming the other tenant)
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
 * &#64;negative &#64;tenant
 * Scenario Outline: A source cannot reach another tenant      (sources of A and B, ADR-007 Decision 2)
 *   Given a source system's machine credential of tenant A
 *   When its call carries &lt;forgery&gt; naming tenant B
 *   Then no tenant-B row is returned and nothing is written outside tenant A
 *   And the forgery is recorded as a denied attempt
 *   Examples: a "Tenant" header | a tenantId query parameter | a tenantId field in the body
 *           | a customerId of the other tenant's source in the body (403, nothing written)
 *
 * &#64;negative &#64;tenant
 * Scenario: A token tenant claim cannot choose the tenant      (ADR-007 Decision 2, every endpoint)
 *
 * &#64;negative &#64;security
 * Scenario: No identity, no tenant
 *   Given an unauthenticated request that carries a tenant field
 *   When it is handled
 *   Then it is rejected with 401 and no tenant data is read
 * </pre>
 *
 * <p>Container group (R03), one Docker suite at a time (R06). It moves to platform-e2e when that module exists.
 *
 * <p>The lifecycle endpoints of the config admin API (T-015/T-019/T-021) write rows, so the matrix carries a
 * row-state probe per write entry ({@link WriteProbe}): the suite re-seeds both tenants' config chains before
 * every test (fixed ids, guard-trigger-safe inserts) and after every call checks that the calling tenant's rows
 * stand where the scenario says (a legitimate write lands; an ignored forgery still lands; a foreign id, a refusal
 * and a 401 write nothing) and that the other tenant's whole config chain is byte-identical to its seeded
 * fingerprint.
 */
@Tag("container")
@Tag("tenant")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@Import({TestProbeController.class, ProbeRowReader.class})
@DisplayName("E1-F1-S1 A forged tenant changes nothing (release gate) - E4-F1-S4 re-run with Keycloak tokens")
class ForgedTenantDenialSuiteTest {

    /** The backend port is fixed before Keycloak starts: the console client's redirect URI must name it. */
    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static final TestTokens TOKENS = new TestTokens(KEYCLOAK, BACKEND);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static RestrictedLogin login;
    private static Seed seed;
    private static final Map<Caller, ConsoleSession> SESSIONS = new EnumMap<>(Caller.class);
    private static final Map<Caller, ClientCredentials> SOURCES = new EnumMap<>(Caller.class);
    /** The realm users behind the callers (filled while seeding, logged in once the directory rows exist). */
    private static final Map<Caller, RealmUser> REALM_USERS = new EnumMap<>(Caller.class);
    private static final Map<UUID, String> FINGERPRINT_BEFORE_TEST = new HashMap<>();

    /** Who calls: a user or a source of tenant A or B, or a user whose token claims the other tenant. */
    enum Caller {
        USER_A("a tenant-A user", true, Kind.USER),
        USER_B("a tenant-B user", false, Kind.USER),
        SOURCE_A("a tenant-A source", true, Kind.SOURCE),
        SOURCE_B("a tenant-B source", false, Kind.SOURCE),
        CLAIM_A("a tenant-A user whose token claims tenant B", true, Kind.CLAIM),
        CLAIM_B("a tenant-B user whose token claims tenant A", false, Kind.CLAIM);

        enum Kind { USER, SOURCE, CLAIM }

        private final String label;
        private final boolean ofTenantA;
        private final Kind kind;

        Caller(String label, boolean ofTenantA, Kind kind) {
            this.label = label;
            this.ofTenantA = ofTenantA;
            this.kind = kind;
        }

        static Stream<Caller> of(Kind kind) {
            return Stream.of(values()).filter(caller -> caller.kind == kind);
        }

        /** The seed seen from this caller: {@code a} is its own tenant, {@code b} the other one. */
        Seed view() {
            return ofTenantA ? seed : seed.swapped();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start(); // idempotent; the issuer must be known before the context starts
        registry.add("server.port", () -> APP_PORT);
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_OIDC_CLIENT_ID", () -> KeycloakRealmContainer.CONSOLE_CLIENT);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", KEYCLOAK::consoleClientSecret);
        registry.add("CONSOLE_OIDC_REDIRECT_URI", () -> BACKEND + "/login/oauth2/code/keycloak");
        registry.add("CONSOLE_POST_LOGOUT_REDIRECT_URI", () -> BACKEND + "/");
        registry.add("CONSOLE_URL", () -> "/");
        // T-124 save-time host check: E5SystemMatrix edits baseUrl to edited.partner.example
        registry.add("platform.config.destinations.allowed-hosts", () -> "partner.example,*.partner.example");
    }

    @MockitoSpyBean
    private TenantDenialRecorder recorder;

    @Autowired
    private ProbeRowReader probe;

    @Autowired
    private List<ApiOperationRules> ruleSets;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void seedOnceThenReset() throws Exception {
        if (seed == null) {
            seed = seedTenantsAandB();
            for (Caller caller : Caller.values()) {
                if (caller.kind != Caller.Kind.SOURCE) {
                    SESSIONS.put(caller, TOKENS.login(REALM_USERS.get(caller)));
                }
            }
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            // E5 seam (F13): lane rows go first (they may reference the core chain) and come back after it
            for (LaneMatrix lane : EndpointMatrix.LANES) {
                lane.clearTenantRows(owner, seed.a());
                lane.clearTenantRows(owner, seed.b());
            }
            writeConfigChain(owner, seed.a());
            writeConfigChain(owner, seed.b());
            for (LaneMatrix lane : EndpointMatrix.LANES) {
                lane.writeTenantRows(owner, seed.a());
                lane.writeTenantRows(owner, seed.b());
            }
            FINGERPRINT_BEFORE_TEST.put(seed.a().id(), configFingerprint(owner, seed.a().id()));
            FINGERPRINT_BEFORE_TEST.put(seed.b().id(), configFingerprint(owner, seed.b().id()));
        }
        clearInvocations(recorder);
        probe.resetReads();
    }

    static Stream<Object[]> callersAndEndpoints() {
        return Stream.concat(Caller.of(Caller.Kind.USER), Caller.of(Caller.Kind.SOURCE))
                .flatMap(caller -> EndpointMatrix.REGISTRY.stream().map(entry -> new Object[] {caller, entry}));
    }

    static Stream<Object[]> userForgeries() {
        return Caller.of(Caller.Kind.USER).flatMap(caller -> EndpointMatrix.forgeries()
                .map(pair -> new Object[] {caller, pair[0], pair[1]}));
    }

    static Stream<Object[]> sourceForgeries() {
        return Caller.of(Caller.Kind.SOURCE).flatMap(caller -> EndpointMatrix.sourceForgeries()
                .map(pair -> new Object[] {caller, pair[0], pair[1]}));
    }

    static Stream<Object[]> claimsAndEndpoints() {
        return Caller.of(Caller.Kind.CLAIM)
                .flatMap(caller -> EndpointMatrix.REGISTRY.stream().map(entry -> new Object[] {caller, entry}));
    }

    static Stream<Object[]> tenantFields() {
        return EndpointMatrix.tenantFields();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("callersAndEndpoints")
    @DisplayName("Scenario: A tenant-A user sees only tenant-A rows (users and sources of A and B)")
    void eachCallerSeesOnlyItsOwnTenantsRows(Caller caller, Entry entry) throws Exception {
        Seed view = caller.view();
        HttpResponse<String> response = send(view, entry, null, null, caller);

        if (caller.kind == Caller.Kind.SOURCE && !sourceReaches(entry)) {
            assertRoleRefusal(view, entry, response);
        } else if (caller.kind == Caller.Kind.USER && !humanReaches(entry)) {
            // Row 278 T104 (arjun-approved): a human session on a source-only endpoint (intake's SUBMIT_MESSAGE is
            // the first such entry) is refused by the role interceptor before the handler - the mirror of the
            // machine leg above.
            assertRoleRefusal(view, entry, response);
        } else {
            assertThat(response.statusCode()).as(caller + " on " + entry + " status, body: " + response.body())
                    .isEqualTo(entry.expectedStatus());
            assertOnlyCallerData(view, entry, response);
            assertRowState(view, entry, "the legitimate call wrote the caller's own rows", false);
        }
        assertOtherTenantUnchanged(view);
        verify(recorder, never()).record(any());
    }

    @ParameterizedTest(name = "{0}: {2} on {1}")
    @MethodSource("userForgeries")
    @Tag("negative")
    @DisplayName("Scenario Outline: A forged tenant is ignored (users of A and B)")
    void aForgedTenantIsIgnored(Caller caller, Entry entry, Forgery forgery) throws Exception {
        Seed view = caller.view();
        HttpResponse<String> response = send(view, entry, forgery, null, caller);

        if (caller.kind == Caller.Kind.USER && !humanReaches(entry)) {
            // Row 278 T104 (arjun-approved): the interceptor refuses the human before the handler runs, so the
            // path-id 404 never fires - and only that 404 advice records PATH_ID, so this leg records no attempt.
            // A tenant-field forgery is still recorded, because the forgery filter runs before the interceptor.
            assertRoleRefusal(view, entry, response);
            assertOtherTenantUnchanged(view);
            if (forgery == Forgery.PATH_ID) {
                verify(recorder, never()).record(any());
            } else {
                assertTheOneRecordedAttempt(forgery.channel(), view.a().id(), entry, response);
            }
            return;
        }
        if (forgery == Forgery.PATH_ID) {
            assertThat(response.statusCode()).as(entry + " with the other tenant's id, body: " + response.body())
                    .isEqualTo(404);
            assertNoMarkerOf(view.b(), response);
            assertRowState(view, entry, "the other tenant's id reached no row of the caller", true);
        } else {
            assertThat(response.statusCode()).as(entry + " with " + forgery + ", body: " + response.body())
                    .isEqualTo(entry.expectedStatus());
            assertOnlyCallerData(view, entry, response);
            assertRowState(view, entry, "the ignored forgery still wrote the caller's own rows only", false);
        }
        assertOtherTenantUnchanged(view);
        assertTheOneRecordedAttempt(forgery.channel(), view.a().id(), entry, response);
    }

    @ParameterizedTest(name = "{0}: {2} on {1}")
    @MethodSource("sourceForgeries")
    @Tag("negative")
    @DisplayName("Scenario Outline: A source cannot reach another tenant (sources of A and B)")
    void aSourceCannotReachAnotherTenant(Caller caller, Entry entry, SourceForgery forgery) throws Exception {
        Seed view = caller.view();
        String foreignCustomer = forgery == SourceForgery.BODY_CUSTOMER ? view.b().customerId() : null;
        HttpResponse<String> response = send(view, entry, forgery.asTenantForgery(), foreignCustomer, caller);

        if (forgery == SourceForgery.BODY_CUSTOMER) {
            assertThat(response.statusCode()).as(entry + " with another customer, body: " + response.body())
                    .isEqualTo(403);
            assertNoMarkerOf(view.b(), response);
            assertThat(probe.reads()).as("tenant data reads").isZero();
            assertRowState(view, entry, "a refused customer wrote nothing", true);
        } else if (sourceReaches(entry)) {
            assertThat(response.statusCode()).as(entry + " with " + forgery + ", body: " + response.body())
                    .isEqualTo(entry.expectedStatus());
            assertOnlyCallerData(view, entry, response);
            assertRowState(view, entry, "the ignored forgery still wrote the caller's own rows only", false);
        } else {
            assertRoleRefusal(view, entry, response);
        }
        assertOtherTenantUnchanged(view);
        assertTheOneRecordedAttempt(forgery.channel(), view.a().id(), entry, response);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("claimsAndEndpoints")
    @Tag("negative")
    @DisplayName("Scenario: A token tenant claim cannot choose the tenant")
    void aTokenTenantClaimCannotChooseTheTenant(Caller caller, Entry entry) throws Exception {
        Seed view = caller.view();
        HttpResponse<String> response = send(view, entry, null, null, caller);

        assertThat(response.statusCode()).as(entry + " with a forged tenant claim, body: " + response.body())
                .isEqualTo(403);
        assertNoMarkerOf(view.b(), response);
        assertThat(probe.reads()).as("tenant data reads").isZero();
        assertRowState(view, entry, "a forged claim wrote nothing", true);
        assertOtherTenantUnchanged(view);
        assertTheOneRecordedAttempt(DeniedTenantAttempt.Channel.TOKEN_CLAIM, view.a().id(), entry, response);
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("tenantFields")
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: No identity, no tenant")
    void noIdentityNoTenant(Entry entry, Forgery tenantField) throws Exception {
        HttpResponse<String> response = send(seed, entry, tenantField, null, null);

        assertThat(response.statusCode()).as(entry + " without identity, body: " + response.body()).isEqualTo(401);
        assertNoMarkerOf(seed.a(), response);
        assertNoMarkerOf(seed.b(), response);
        assertThat(probe.reads()).as("tenant data reads").isZero();
        assertRowState(seed, entry, "no identity, no config write", true);
        assertOtherTenantUnchanged(seed);
        assertOtherTenantUnchanged(seed.swapped());
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

    /**
     * Builds the entry's call for the view's tenant {@code a}, applies the forgery naming tenant {@code b} (null = no
     * forgery) and the foreign customer (null = none), and authenticates as the caller (null = no identity).
     */
    private static HttpResponse<String> send(Seed view, Entry entry, Forgery forgery, String foreignCustomer,
            Caller caller) throws Exception {
        String foreignTenant = view.b().id().toString();
        String path = forgery == Forgery.PATH_ID ? entry.foreignIdPath().apply(view) : entry.pathForA().apply(view);
        if (forgery == Forgery.QUERY_PARAMETER) {
            path += (path.contains("?") ? "&" : "?") + Forgery.TENANT_FIELD + "="
                    + URLEncoder.encode(foreignTenant, StandardCharsets.UTF_8);
        }
        String body = entry.acceptsBody() ? entry.bodyForA().apply(view) : null;
        if (forgery == Forgery.BODY_FIELD || foreignCustomer != null) {
            ObjectNode forged = (ObjectNode) JSON.readTree(body);
            if (forgery == Forgery.BODY_FIELD) {
                forged.put(Forgery.TENANT_FIELD, foreignTenant);
            }
            if (foreignCustomer != null) {
                forged.put(SourceForgery.CUSTOMER_FIELD, foreignCustomer);
            }
            body = JSON.writeValueAsString(forged);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(BACKEND.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        ConsoleSession session = caller == null ? null : SESSIONS.get(caller);
        if (session != null) {
            session.authenticate(request);
        } else if (caller != null) {
            request.header("Authorization", "Bearer " + TOKENS.sourceToken(SOURCES.get(caller)));
        }
        if (forgery == Forgery.HEADER) {
            request.header(Forgery.TENANT_HEADER, foreignTenant);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        // Row 278 T104 (arjun-approved): the entry's own required headers (intake's contract-version pin and
        // idempotency key); the function runs per request, so a per-call nonce is fresh on every send.
        if (entry.extraHeaders() != null) {
            entry.extraHeaders().apply(view).forEach(request::header);
        }
        request.method(entry.method(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (session != null) {
            session.remember(response);
        }
        return response;
    }

    /**
     * Whether a source's machine credential reaches the handler: an endpoint without a role rule (the test probe) or
     * one for source systems; every role-gated or human-only endpoint refuses a machine principal (T-099).
     */
    private boolean sourceReaches(Entry entry) {
        return ruleSets.stream()
                .flatMap(rules -> rules.rules().stream())
                .filter(rule -> rule.key().equals(entry.key()))
                .findFirst()
                .map(rule -> rule.operation().caller() == RolePolicy.Caller.SOURCE_SYSTEM)
                .orElse(true);
    }

    /**
     * Whether a human session reaches the handler: an endpoint without a role rule or one addressed to humans; a
     * source-only or operator-agent operation refuses every human principal by caller kind before the handler runs
     * (RolePolicy.Caller; intake's SUBMIT_MESSAGE is the first matrix entry this covers, row 278 T104).
     */
    private boolean humanReaches(Entry entry) {
        return ruleSets.stream()
                .flatMap(rules -> rules.rules().stream())
                .filter(rule -> rule.key().equals(entry.key()))
                .findFirst()
                .map(rule -> switch (rule.operation().caller()) {
                    case SOURCE_SYSTEM, OPERATOR_AGENT -> false;
                    case ROLE, ANY_HUMAN -> true;
                })
                .orElse(true);
    }

    // --- assertions ---------------------------------------------------------------------------------------------

    /**
     * An endpoint refuses a principal its operation does not address: 403 with no data of either tenant, no tenant
     * read, nothing written. Which layer answers is not this suite's subject (the role interceptor's ROLE_NOT_GRANTED
     * body, or the handler's own empty 403 as GET /api/v1/me answers today), so the body is checked for data, not for
     * its shape. T104: the same assertion covers a human on a source-only endpoint, not only a machine on a
     * human-only one.
     */
    private void assertRoleRefusal(Seed view, Entry entry, HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(entry + " refused by role, body: " + response.body()).isEqualTo(403);
        assertNoMarkerOf(view.a(), response);
        assertNoMarkerOf(view.b(), response);
        assertThat(probe.reads()).as("tenant data reads").isZero();
        assertRowState(view, entry, "a refused caller wrote nothing", true);
    }

    private static void assertOnlyCallerData(Seed view, Entry entry, HttpResponse<String> response) {
        assertNoMarkerOf(view.b(), response);
        List<String> expectedData = entry.tenantAData().apply(view);
        if (!expectedData.isEmpty()) {
            assertThat(response.body()).as(entry + " answers with the caller's tenant data").contains(expectedData);
        }
        JsonNode json = JSON.readTree(response.body());
        for (JsonNode tenantId : json.findValues(Forgery.TENANT_FIELD)) {
            assertThat(tenantId.asString()).as(entry + " tenantId in the answer").isEqualTo(view.a().id().toString());
        }
    }

    private static void assertNoMarkerOf(SeededTenant tenant, HttpResponse<String> response) {
        for (String marker : tenant.markers()) {
            assertThat(response.body()).as("answer leaks a value of tenant " + tenant.code()).doesNotContain(marker);
        }
    }

    private void assertTheOneRecordedAttempt(DeniedTenantAttempt.Channel channel, UUID boundTenant, Entry entry,
            HttpResponse<String> response) {
        DeniedTenantAttempt attempt = theOneRecordedAttempt();
        assertThat(attempt.channel()).isEqualTo(channel);
        assertThat(attempt.boundTenantId()).isEqualTo(boundTenant);
        assertThat(attempt.method()).isEqualTo(entry.method());
        assertThat(attempt.path()).isEqualTo(response.request().uri().getRawPath());
    }

    private DeniedTenantAttempt theOneRecordedAttempt() {
        ArgumentCaptor<DeniedTenantAttempt> attempt = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder, times(1)).record(attempt.capture());
        return attempt.getValue();
    }

    /** The write entry's row state, read as the owner: {@code expectSeeded} picks seeded vs after-the-write. */
    private static void assertRowState(Seed view, Entry entry, String label, boolean expectSeeded) throws Exception {
        WriteProbe chain = entry.write();
        if (chain == null) {
            return;
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
             PreparedStatement state = owner.prepareStatement(chain.stateSql().apply(view));
             ResultSet rs = state.executeQuery()) {
            assertThat(rs.next()).as(entry.key() + " answers a state row").isTrue();
            assertThat(rs.getString(1)).as(entry.key() + " - " + label)
                    .isEqualTo(expectSeeded ? chain.seeded() : chain.afterWrite());
        }
    }

    /** The view's other tenant: its config chain is byte-identical (ids included) to what the per-test reset wrote. */
    private static void assertOtherTenantUnchanged(Seed view) throws Exception {
        UUID other = view.b().id();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(configFingerprint(owner, other))
                    .as("tenant " + view.b().code() + "'s config chain unchanged by tenant " + view.a().code()
                            + "'s traffic")
                    .isEqualTo(FINGERPRINT_BEFORE_TEST.get(other));
        }
    }

    /** One md5 over every config table's row ids and count for one tenant (the owner bypasses RLS). */
    private static String configFingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement fingerprint = owner.prepareStatement(CONFIG_FINGERPRINT)) {
            for (int position = 1; position <= 9; position++) {
                fingerprint.setString(position, tenantId.toString());
            }
            try (ResultSet rs = fingerprint.executeQuery()) {
                assertThat(rs.next()).as("the fingerprint always answers").isTrue();
                StringBuilder facts = new StringBuilder(rs.getString(1));
                for (LaneMatrix lane : EndpointMatrix.LANES) {
                    facts.append(';').append(lane.fingerprint(owner, tenantId));
                }
                return facts.toString();
            }
        }
    }

    private static final String CONFIG_FINGERPRINT = "SELECT md5(string_agg(fact, ';' ORDER BY fact)) FROM ("
            + " SELECT 'config_version_refusal:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') AS fact"
            + "   FROM public.config_version_refusal WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'contract:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.contract WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'contract_version:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '')"
            + "   FROM public.contract_version WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'interface:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.interface WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'interface_version:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '')"
            + "   FROM public.interface_version WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'mapping:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.mapping WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'route:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.route WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'system:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.system WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'target_endpoint:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '')"
            + "   FROM public.target_endpoint WHERE tenant_id = ?::uuid"
            + ") facts";

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

        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            SeededTenant a = seedTenant(owner, "alpha", idA, idB, Caller.USER_A, Caller.CLAIM_A, Caller.SOURCE_A);
            SeededTenant b = seedTenant(owner, "bravo", idB, idA, Caller.USER_B, Caller.CLAIM_B, Caller.SOURCE_B);
            for (SeededTenant tenant : List.of(a, b)) {
                try (PreparedStatement count = owner.prepareStatement(
                        "SELECT count(*) FROM probe_row WHERE tenant_id = ?::uuid")) {
                    count.setString(1, tenant.id().toString());
                    try (ResultSet rs = count.executeQuery()) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("both tenants have rows, so 'only my tenant' is not vacuous")
                                .isEqualTo(2);
                    }
                }
            }
            return new Seed(a, b);
        }
    }

    /**
     * One tenant: its Keycloak user bound through app_user (issuer + subject) with TENANT_ADMIN, a second bound user
     * whose token claims the other tenant, its source system's Keycloak client resolved by an ACTIVE source_credential
     * row to the tenant and its customer identity, two probe rows and the ids of its config chain.
     */
    private static SeededTenant seedTenant(Connection owner, String word, UUID tenantId, UUID otherTenantId,
            Caller user, Caller claimer, Caller source) throws Exception {
        String code = "tnt-" + word + "-" + tenantId.toString().substring(0, 8);
        execute(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                tenantId, code);

        RealmUser realmUser = TOKENS.user("user-" + word, null);
        REALM_USERS.put(user, realmUser);
        UUID userId = bindUser(owner, tenantId, realmUser);
        RealmUser claimingUser = TOKENS.user("claims-other-" + word, otherTenantId.toString());
        REALM_USERS.put(claimer, claimingUser);
        UUID claimUserId = bindUser(owner, tenantId, claimingUser);

        UUID machineSystemId = UUID.randomUUID();
        String customerId = "cust-" + word + "-" + UUID.randomUUID().toString().substring(0, 8);
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'SOURCE')",
                machineSystemId, tenantId, "mach-" + word, "mach-" + word + " machine source");
        ClientCredentials client = TOKENS.source("source-" + word + "-" + UUID.randomUUID().toString().substring(0, 8));
        SOURCES.put(source, client);
        execute(owner, "INSERT INTO public.source_credential (tenant_id, source_system_id, customer_id, issuer,"
                        + " client_id, status) VALUES (?::uuid, ?::uuid, ?, ?, ?, 'ACTIVE')",
                tenantId, machineSystemId, customerId, KEYCLOAK.issuerUri(), client.clientId());

        List<UUID> rowIds = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            UUID rowId = UUID.randomUUID();
            String label = word + "-" + i;
            execute(owner, "INSERT INTO probe_row (id, tenant_id, label) VALUES (?::uuid, ?::uuid, ?)",
                    rowId, tenantId, label);
            rowIds.add(rowId);
            labels.add(label);
        }
        Config config = new Config(word,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return new SeededTenant(tenantId, code, userId, realmUser.subject(), claimUserId, machineSystemId, customerId,
                client.clientId(), List.copyOf(rowIds), List.copyOf(labels), config);
    }

    /**
     * Binds a realm user to the tenant by (issuer, subject) - ADR-007 Decision 2 - and grants TENANT_ADMIN (E4 T-099:
     * roles are role_grant rows; the matrix user drives every admin operation, so no denial is a missing role).
     */
    private static UUID bindUser(Connection owner, UUID tenantId, RealmUser realmUser) throws Exception {
        UUID userId = UUID.randomUUID();
        execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')",
                userId, tenantId, KEYCLOAK.issuerUri(), realmUser.subject());
        execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')",
                tenantId, userId);
        return userId;
    }

    /**
     * Rewrites one tenant's config chain to exactly the seeded rows: every lifecycle endpoint can run per test
     * without order effects. The delete/insert pair is guard-trigger-safe (the V2_2_4 trigger fires on UPDATE
     * only) and satisfies every composite FK inside the tenant, so the chain stands where the matrix expects it:
     * two systems and an endpoint, the source contract with its 1.0 draft, and six flows - plain, draft (PUT),
     * submittable, approvable (authored by a different user), publishable (APPROVED snapshot of one route and
     * one mapping) and immutable (the version-edit refusal target). The machine credential's source system is
     * seeded once and kept (its source_credential row references it; that table denies DELETE by design).
     */
    private static void writeConfigChain(Connection owner, SeededTenant tenant) throws Exception {
        for (String table : List.of("config_version_refusal", "interface_version", "mapping", "route",
                "contract_version", "contract", "target_endpoint", "interface")) {
            execute(owner, "DELETE FROM public." + table + " WHERE tenant_id = ?::uuid", tenant.id());
        }
        execute(owner, "DELETE FROM public.system WHERE tenant_id = ?::uuid AND id <> ?::uuid", tenant.id(),
                tenant.machineSystemId());
        Config c = tenant.config();
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'SOURCE')",
                c.srcSystemId(), tenant.id(), c.srcCode(), c.srcCode() + " source");
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'TARGET')",
                c.dstSystemId(), tenant.id(), c.dstCode(), c.dstCode() + " target");
        execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                        + " http_method, auth_method, timeout_ms)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'https://endpoint.invalid/', 'POST',"
                        + " 'NO_AUTH', 5000)",
                c.dstEndpointId(), tenant.id(), c.dstSystemId(), c.dstCode() + "-default", c.dstCode() + " endpoint");
        execute(owner, "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', ?)",
                c.contractId(), tenant.id(), c.srcSystemId(), c.srcCode(), c.srcCode() + " contract");
        execute(owner, "INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
                        + " json_schema_draft_2020_12, author_id)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, '1.0', 'DRAFT', '{\"type\":\"object\"}'::jsonb,"
                        + " ?::uuid)",
                c.sourceContractVersionId(), tenant.id(), c.contractId(), tenant.userId());
        insertFlow(owner, tenant, c, "main", c.flowMainId(), "DRAFT", null, tenant.userId(), null);
        insertFlow(owner, tenant, c, "draft", c.flowDraftId(), "DRAFT", null, tenant.userId(), null);
        insertFlow(owner, tenant, c, "submit", c.flowSubmitId(), "DRAFT", null, tenant.userId(), null);
        insertFlow(owner, tenant, c, "approve", c.flowApproveId(), "PENDING_APPROVAL", null, c.otherAuthorId(), null);
        insertFlow(owner, tenant, c, "publish", c.flowPublishId(), "APPROVED", publishSnapshot(c, tenant),
                c.otherAuthorId(), tenant.userId());
        insertFlow(owner, tenant, c, "immutable", c.flowImmutableId(), "DRAFT", null, tenant.userId(), null);
    }

    /** The only version id the matrix names outside a status query is the immutable flow's (the refusal target). */
    private static void insertFlow(Connection owner, SeededTenant tenant, Config c, String kind, UUID flowId,
            String status, String snapshot, UUID authorId, UUID approverId) throws Exception {
        execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name, ordering, current_version)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'PER_KEY', 1)",
                flowId, tenant.id(), c.flowCode(kind), c.flowCode(kind) + " " + kind);
        // The lifecycle timestamps are SQL literals, not parameters: a varchar-bound "now()" is not assignable
        // to a timestamptz column, and NULL keeps the DRAFT row's shape honest (nothing was submitted yet).
        String submitted = status.equals("PENDING_APPROVAL") || status.equals("APPROVED") ? "now()" : "NULL";
        String decided = status.equals("APPROVED") ? "now()" : "NULL";
        execute(owner, "INSERT INTO public.interface_version (id, tenant_id, interface_id, version, status,"
                        + " business_key_rule, snapshot, source_contract_version_id, author_id, approver_id,"
                        + " submitted_at, decided_at)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, ?, '$.orderId', ?::jsonb, ?::uuid, ?::uuid,"
                        + " ?::uuid, " + submitted + ", " + decided + ")",
                kind.equals("immutable") ? c.immutableVersionId() : UUID.randomUUID(), tenant.id(), flowId, status,
                snapshot == null ? "{\"interface\":{},\"routes\":[],\"mappings\":[]}" : snapshot,
                c.sourceContractVersionId(), authorId, approverId);
    }

    /**
     * The APPROVED version's bundle, in the one serialization the publish materializes (T-021). Since the
     * publish-time pin check (T-190, ADR-013 Decision 5) the entries carry the bindings and the pinned mapping
     * version id - the same content the lane's writeTenantRows seeds, so the check verifies 200 on publish.
     */
    private static String publishSnapshot(Config c, SeededTenant tenant) {
        String source = c.sourceContractVersionId().toString();
        String target = E7MappingMatrix.targetCvId(tenant).toString();
        return FlowBundle.serialize(c.flowCode("publish"), c.flowCode("publish") + " publish", "PER_KEY",
                "$.orderId",
                List.of(new RouteRow(c.pubRouteId().toString(), 1, null, false, "ACTIVE",
                        c.dstEndpointId().toString())),
                List.of(new MappingRow(c.pubMappingId().toString(), c.pubRouteId().toString(), "[]", "$",
                        source, target, E7MappingMatrix.publishMappingVersionIdOf(tenant).toString())));
    }

    private static void execute(Connection owner, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                Object value = values[position];
                if (value == null) {
                    statement.setNull(position + 1, Types.OTHER);
                } else {
                    statement.setObject(position + 1, value instanceof UUID id ? id.toString() : value);
                }
            }
            statement.executeUpdate();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
