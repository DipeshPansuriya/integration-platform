package io.integrationplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.identity.ConsoleBrowser.Exchange;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * E4-F1-S1 "Log in before seeing anything" (T-097) end to end: the real application, PostgreSQL and the pinned Keycloak
 * with realm {@code platform} imported from the compose realm file. Every login goes through the realm's real login
 * form ({@link ConsoleBrowser}); the console's browser twin is the Playwright suite in {@code ui/tests/screens}.
 * Also ADR-007 Decision 1: no access token ever reaches the browser, the session cookie is HttpOnly + Secure +
 * SameSite=Lax and a state-changing session request without the CSRF header is refused. Container group (R03).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ConsoleLoginContainerTest {

    /** The backend port is fixed before Keycloak starts: the realm's redirect URI must name it. */
    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{4,}\\.eyJ[A-Za-z0-9_-]{4,}\\.");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();
    static final UUID TENANT_SUSPENDED = UUID.randomUUID();

    /** alice: tenant A; bob: tenant B; carol: DISABLED user of A; dave: user of a SUSPENDED tenant. */
    static RealmUser alice;
    static RealmUser bob;
    static RealmUser carol;
    static RealmUser dave;
    /** mallory: a user of tenant A whose token carries tenant=B (a test-only claim mapper). */
    static RealmUser mallory;
    /** eve: bound to tenant A but disabled in Keycloak. */
    static RealmUser eve;

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
    }

    @Autowired
    private LoginBootstrap loginBootstrap;

    /** Once, after the application migrated the schema: the directory rows for the realm's users (owner login). */
    @BeforeEach
    void seedTheDirectory() throws SQLException {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        alice = KEYCLOAK.createUser("alice");
        bob = KEYCLOAK.createUser("bob");
        carol = KEYCLOAK.createUser("carol");
        dave = KEYCLOAK.createUser("dave");
        mallory = KEYCLOAK.createUser("mallory", Map.of("tenant", TENANT_B.toString()));
        eve = KEYCLOAK.createUser("eve");
        KEYCLOAK.disableUser(eve);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenant(owner, TENANT_A, "e4-tenant-a", "ACTIVE");
            tenant(owner, TENANT_B, "e4-tenant-b", "ACTIVE");
            tenant(owner, TENANT_SUSPENDED, "e4-tenant-c", "SUSPENDED");
            user(owner, TENANT_A, alice, "ACTIVE");
            user(owner, TENANT_B, bob, "ACTIVE");
            user(owner, TENANT_A, carol, "DISABLED");
            user(owner, TENANT_SUSPENDED, dave, "ACTIVE");
            user(owner, TENANT_A, mallory, "ACTIVE");
            user(owner, TENANT_A, eve, "ACTIVE");
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
    }

    // ------------------------------------------------------------------ Scenario: Login, then own tenant only

    @Test
    @DisplayName("Scenario: Login, then own tenant only")
    void loginThenOwnTenantOnly() {
        ConsoleBrowser aliceBrowser = loggedIn(alice);
        ConsoleBrowser bobBrowser = loggedIn(bob);
        String aliceFlow = seedFlow(aliceBrowser);
        String bobFlow = seedFlow(bobBrowser);

        Exchange me = aliceBrowser.api("GET", "/api/v1/me", null, false);
        assertThat(me.status()).isEqualTo(200);
        assertThat(json(me).get("tenantId").asString()).isEqualTo(TENANT_A.toString());

        Exchange flows = aliceBrowser.api("GET", "/api/v1/admin/flows", null, false);
        assertThat(flows.status()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json(flows).forEach(flow -> ids.add(flow.get("id").asString()));
        assertThat(ids).contains(aliceFlow).doesNotContain(bobFlow);
        assertThat(aliceBrowser.api("GET", "/api/v1/admin/flows/" + bobFlow, null, false).status())
                .as("tenant B's flow by id").isEqualTo(404);
        assertThat(flows.body()).doesNotContain(TENANT_B.toString());
    }

    // ------------------------------------------------------------------ @negative @security Scenario: No session, no data

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("@negative @security Scenario: No session, no data")
    void noSessionNoData() {
        JsonNode document = json(loggedIn(alice).api("GET", "/v3/api-docs", null, false));
        ConsoleBrowser anonymous = KEYCLOAK.browser(BACKEND);
        ConsoleBrowser deadSession = KEYCLOAK.browser(BACKEND);
        deadSession.setCookie("SESSION", UUID.randomUUID().toString());

        List<String> leaks = new ArrayList<>();
        int operations = 0;
        for (var path : document.get("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) {
                continue;
            }
            String concrete = path.getKey().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            for (var operation : path.getValue().properties()) {
                String method = operation.getKey().toUpperCase();
                String body = List.of("POST", "PUT", "PATCH").contains(method) ? "{}" : null;
                operations++;
                for (ConsoleBrowser browser : List.of(anonymous, deadSession)) {
                    Exchange answer = browser.api(method, concrete, body, false);
                    if (answer.status() != 401 || answer.location().isPresent()) {
                        leaks.add(method + " " + path.getKey() + " -> " + answer.status() + " "
                                + answer.location().orElse(""));
                    }
                }
            }
        }
        assertThat(operations).as("every /api operation of the live springdoc document").isGreaterThan(5);
        assertThat(leaks).as("no session: every API call is a 401, never data, never a redirect").isEmpty();

        Exchange page = anonymous.get("/oauth2/authorization/keycloak");
        assertThat(page.status()).isEqualTo(302);
        assertThat(page.location().orElseThrow()).startsWith(KEYCLOAK.issuerUri() + "/protocol/openid-connect/auth");
    }

    // ------------------------------------------------------------------ @negative @tenant Scenario: token tenant claim

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@negative @tenant Scenario: A token tenant claim cannot choose the tenant")
    void aTokenTenantClaimCannotChooseTheTenant(CapturedOutput output) {
        ConsoleBrowser browser = loggedIn(mallory);

        Exchange me = browser.api("GET", "/api/v1/me", null, false);
        Exchange flows = browser.api("GET", "/api/v1/admin/flows", null, false);

        assertThat(me.status()).isEqualTo(403);
        assertThat(flows.status()).isEqualTo(403);
        assertThat(flows.body()).doesNotContain(TENANT_B.toString());
        assertThat(output.getAll().lines().filter(line -> line.contains("event=tenant.denied")))
                .anyMatch(line -> line.contains("channel=TOKEN_CLAIM") && line.contains("boundTenant=" + TENANT_A));
    }

    // ------------------------------------------------------------------ @negative @security Scenario: inactive

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("@negative @security Scenario: An inactive user or tenant cannot log in")
    void anInactiveUserOrTenantCannotLogIn() {
        for (RealmUser inactive : List.of(carol, dave)) {
            assertThat(loginBootstrap.resolve(KEYCLOAK.issuerUri(), inactive.subject()))
                    .as("the bootstrap returns nothing for %s", inactive).isEmpty();
            ConsoleBrowser browser = loggedIn(inactive);
            assertThat(browser.api("GET", "/api/v1/me", null, false).status()).as(inactive.username()).isEqualTo(403);
            assertThat(browser.api("GET", "/api/v1/admin/flows", null, false).status()).isEqualTo(403);
        }

        ConsoleBrowser disabledInKeycloak = KEYCLOAK.browser(BACKEND);
        Exchange refused = disabledInKeycloak.login(eve);
        assertThat(refused.uri().toString()).as("stays at the identity provider").startsWith(KEYCLOAK.issuerUri());
        assertThat(disabledInKeycloak.api("GET", "/api/v1/me", null, false).status()).isEqualTo(401);
        assertThat(loginBootstrap.resolve(KEYCLOAK.issuerUri(), alice.subject())).as("the active user resolves")
                .isPresent();
    }

    // ------------------------------------------------------------------ ADR-007 Decision 1 checks

    @Test
    @Tag("security")
    @DisplayName("Decision 1: no response body or script-readable cookie ever carries an access token")
    void noBodyOrScriptReadableCookieEverCarriesAnAccessToken() {
        ConsoleBrowser browser = loggedIn(alice);
        browser.api("GET", "/api/v1/me", null, false);
        browser.api("GET", "/api/v1/admin/flows", null, false);
        seedFlow(browser);

        List<Exchange> backend = browser.backendHistory();
        assertThat(backend).hasSizeGreaterThan(4);
        for (Exchange exchange : backend) {
            assertThat(JWT.matcher(exchange.body()).find()).as("body of %s %s", exchange.method(), exchange.uri())
                    .isFalse();
            for (String cookie : exchange.setCookies()) {
                assertThat(JWT.matcher(cookie).find()).as("cookie %s", cookie).isFalse();
            }
        }
        assertThat(browser.history()).filteredOn(e -> e.location().orElse("").contains("/login/oauth2/code/"))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.location().orElseThrow()).contains("code=")
                        .doesNotContain("access_token").doesNotContain("id_token"));
    }

    @Test
    @Tag("security")
    @DisplayName("Decision 1: the session cookie is HttpOnly, Secure, SameSite=Lax")
    void theSessionCookieIsHttpOnlySecureSameSiteLax() {
        ConsoleBrowser browser = loggedIn(alice);

        List<String> sessionCookies = browser.backendHistory().stream()
                .flatMap(e -> e.setCookies().stream())
                .filter(cookie -> cookie.startsWith("SESSION="))
                .toList();
        assertThat(sessionCookies).isNotEmpty().allSatisfy(cookie -> assertThat(cookie)
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Lax"));
    }

    @Test
    @Tag("security")
    @DisplayName("Decision 1: a state-changing session request without the CSRF header answers 403")
    void aStateChangingSessionRequestWithoutTheCsrfHeaderAnswers403() throws SQLException {
        ConsoleBrowser browser = loggedIn(alice);
        browser.api("GET", "/api/v1/me", null, false); // the console's first call hands out the CSRF cookie
        String code = "CSRF" + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);

        Exchange refused = browser.api("POST", "/api/v1/admin/systems",
                "{\"code\":\"" + code + "\",\"name\":\"csrf probe\",\"direction\":\"INBOUND\"}", false);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(systemRows(code)).as("nothing persisted").isZero();
        Exchange accepted = browser.api("POST", "/api/v1/admin/systems",
                "{\"code\":\"" + code + "\",\"name\":\"csrf probe\",\"direction\":\"INBOUND\"}", true);
        assertThat(accepted.status()).as("the same call with the header").isEqualTo(201);
    }

    @Test
    @Tag("security")
    @DisplayName("Logout ends the session at the platform and names the identity provider's sign-out")
    void logoutEndsTheSession() {
        ConsoleBrowser browser = loggedIn(alice);
        browser.api("GET", "/api/v1/me", null, false);

        Exchange logout = browser.api("POST", "/logout", null, true);

        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.body()).contains(KEYCLOAK.issuerUri() + "/protocol/openid-connect/logout");
        assertThat(browser.api("GET", "/api/v1/me", null, false).status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static ConsoleBrowser loggedIn(RealmUser user) {
        ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
        Exchange last = browser.login(user);
        assertThat(last.status()).as("login of %s ends in the backend's redirect to the console", user).isEqualTo(302);
        assertThat(last.uri().getPath()).isEqualTo("/login/oauth2/code/keycloak");
        assertThat(browser.cookie("SESSION")).isPresent();
        return browser;
    }

    /** One source system and one flow of the browser's tenant, through the real config API (CSRF header on). */
    private static String seedFlow(ConsoleBrowser browser) {
        browser.api("GET", "/api/v1/me", null, false);
        Exchange system = browser.api("POST", "/api/v1/admin/systems",
                "{\"code\":\"S\",\"name\":\"orders source\",\"direction\":\"INBOUND\"}", true);
        assertThat(system.status()).isIn(201, 409);
        Exchange flow = browser.api("POST", "/api/v1/admin/flows",
                "{\"sourceSystem\":\"S\",\"destinations\":[\"T1\"],\"businessKeyRule\":\"customer_id\"}", true);
        assertThat(flow.status()).as(flow.body()).isEqualTo(201);
        return json(flow).get("id").asString();
    }

    private static JsonNode json(Exchange exchange) {
        return KeycloakRealmContainer.read(exchange.body());
    }

    private static long systemRows(String code) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement count = owner.prepareStatement("select count(*) from public.system where code = ?")) {
            count.setString(1, code);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void tenant(Connection owner, UUID id, String code, String status) throws SQLException {
        try (PreparedStatement insert = owner.prepareStatement(
                "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, ?, 'Etc/UTC')")) {
            insert.setString(1, id.toString());
            insert.setString(2, code);
            insert.setString(3, status);
            insert.executeUpdate();
        }
    }

    private static void user(Connection owner, UUID tenant, RealmUser user, String status) throws SQLException {
        try (PreparedStatement insert = owner.prepareStatement("insert into public.app_user"
                + " (id, tenant_id, issuer, subject, status) values (?::uuid, ?::uuid, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, tenant.toString());
            insert.setString(3, KEYCLOAK.issuerUri());
            insert.setString(4, user.subject());
            insert.setString(5, status);
            insert.executeUpdate();
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
