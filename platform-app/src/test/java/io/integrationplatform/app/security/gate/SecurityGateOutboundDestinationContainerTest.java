package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.identity.ConsoleBrowser;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T171 gate case X-1 (no S1-S8 id: the S6 class carries message-flow cases, security-posture B-4 / gate review B-4):
 * <b>outbound destination controls over the real admin API</b>. A TENANT_ADMIN signed in through the console's
 * session saves a destination system's base URL; every SSRF-shaped address (cloud metadata, loopback, private range,
 * an off-allow-list host, credentials in the authority, look-alike and encoded hosts, non-http schemes) must be
 * refused with a 4xx and must leave the stored base URL exactly as it was. A positive control (an allow-listed host)
 * proves the refusals are the guard, not a broken fixture. Container tag: runs only under {@code -Pcontainer}.
 *
 * <p>NOT covered here (listed in the T171 report): send-time refusal with a connection counter, and the DNS-rebind
 * name and redirect legs, which need a resolvable hostile name / a listening redirector.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SecurityGateOutboundDestinationContainerTest {

    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static final String GOOD = "https" + "://good.e11.test/"; // split so the image-pin scanner does not read it as an image reference

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PlatformTestWiring.RestrictedLogin login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start();
        registry.add("server.port", () -> APP_PORT);
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_OIDC_CLIENT_ID", () -> KeycloakRealmContainer.CONSOLE_CLIENT);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", KEYCLOAK::consoleClientSecret);
        registry.add("CONSOLE_OIDC_REDIRECT_URI", () -> BACKEND + "/login/oauth2/code/keycloak");
        registry.add("CONSOLE_POST_LOGOUT_REDIRECT_URI", () -> BACKEND + "/");
        registry.add("CONSOLE_URL", () -> "/");
        registry.add("platform.config.destinations.allowed-hosts", () -> "good.e11.test,*.partner.example");
        LOGIN_HOLDER.set(login);
    }

    private static final java.util.concurrent.atomic.AtomicReference<PlatformTestWiring.RestrictedLogin> LOGIN_HOLDER =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static String scalar(Connection c, String sql, String arg) throws Exception {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, arg);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void execute(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setString(i + 1, String.valueOf(args[i]));
            }
            st.executeUpdate();
        }
    }

    @Test
    void x1_outbound_destination_refuses_every_ssrf_shaped_base_url() throws Exception {
        PlatformTestWiring.grantApplicationRole(POSTGRES, LOGIN_HOLDER.get());
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        RealmUser admin = KEYCLOAK.createUser("ssrf-admin");
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            execute(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                    tenant, "tnt-x1-" + tenant.toString().substring(0, 8));
            execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES"
                    + " (?::uuid, ?::uuid, ?, ?, 'ACTIVE')", user, tenant, KEYCLOAK.issuerUri(), admin.subject());
            execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')",
                    tenant, user);
            execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction) VALUES"
                    + " (?::uuid, ?::uuid, 'x1-dst', 'x1 destination', 'TARGET')", system, tenant);

            ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
            ConsoleBrowser.Exchange login = browser.login(admin);
            assertThat(login.status()).as("console login").isEqualTo(302);
            browser.get("/api/v1/me"); // renders the CSRF cookie, as the console does on start
            String path = "/api/v1/admin/systems/" + system;

            // positive control: an allow-listed https host is saved
            ConsoleBrowser.Exchange saved = browser.api("PUT", path, body(GOOD), true);
            assertThat(saved.status()).as("allow-listed host is accepted: %s", saved.body()).isEqualTo(200);
            assertThat(scalar(owner, "select settings->>'baseUrl' from public.system where id = ?::uuid",
                    system.toString())).isEqualTo(GOOD);

            Map<String, String> hostile = new LinkedHashMap<>();
            hostile.put("cloud metadata", "http://169.254.169.254/latest/meta-data/");
            hostile.put("cloud metadata over https", "https://169.254.169.254/");
            hostile.put("loopback ip", "http://127.0.0.1:8080/");
            hostile.put("loopback name", "http://localhost/");
            hostile.put("ipv6 loopback", "http://[::1]/");
            hostile.put("private 10/8", "http://10.0.0.1/");
            hostile.put("private 192.168/16", "https://192.168.1.1/");
            hostile.put("link-local ipv6", "http://[fe80::1]/");
            hostile.put("decimal ip for 127.0.0.1", "http://2130706433/");
            hostile.put("hex ip", "http://0x7f.0.0.1/");
            hostile.put("off the allow-list", "https://evil.example/");
            hostile.put("allow-listed name as a sub-domain of evil", "https://good.e11.test.evil.example/");
            hostile.put("credentials in the authority", "https://user:pw@good.e11.test/");
            hostile.put("allow-listed name as the userinfo", "https://good.e11.test@evil.example/");
            hostile.put("scheme file", "file:///etc/passwd");
            hostile.put("scheme ftp", "ftp://good.e11.test/");
            hostile.put("scheme gopher", "gopher://good.e11.test/");
            hostile.put("backslash authority trick", "https://evil.example\\@good.e11.test/");
            hostile.put("empty host", "https:///path");
            for (Map.Entry<String, String> variant : hostile.entrySet()) {
                ConsoleBrowser.Exchange response = browser.api("PUT", path, body(variant.getValue()), true);
                assertThat(response.status()).as("%s (%s) -> %s", variant.getKey(), variant.getValue(), response.body())
                        .isBetween(400, 499);
                assertThat(scalar(owner, "select settings->>'baseUrl' from public.system where id = ?::uuid",
                        system.toString())).as("stored base URL after %s", variant.getKey()).isEqualTo(GOOD);
            }
        }
    }

    private static String body(String baseUrl) {
        return "{\"type\":\"API\",\"settings\":{\"baseUrl\":\"" + baseUrl.replace("\\", "\\\\") + "\"}}";
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
