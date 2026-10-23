package io.integrationplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.identity.ConsoleBrowser.Exchange;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * B33 (row 372): a tampered SESSION cookie must answer 401 and must never cost the pool a connection. Cookie-only, no
 * login: before the fix the cookie values that decode to a session id containing a NUL byte made the Spring Session
 * JDBC lookup fail in PostgreSQL (SQLSTATE 22021, a DataIntegrityViolationException), the filter chain answered 500 and
 * the unauthenticated caller could drain the pool. The real application, PostgreSQL and the pinned Keycloak. Container
 * group (R03).
 */
@Tag("container")
@Tag("security")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ExtendWith(OutputCaptureExtension.class)
class TamperedSessionCookieContainerTest {

    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();
    private static final UUID TENANT = UUID.randomUUID();
    private static RealmUser alice;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start();
        registry.add("server.port", () -> APP_PORT);
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_OIDC_CLIENT_ID", () -> KeycloakRealmContainer.CONSOLE_CLIENT);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", KEYCLOAK::consoleClientSecret);
        registry.add("CONSOLE_OIDC_REDIRECT_URI", () -> BACKEND + "/login/oauth2/code/keycloak");
        registry.add("CONSOLE_POST_LOGOUT_REDIRECT_URI", () -> BACKEND + "/");
        registry.add("CONSOLE_URL", () -> "/");
    }

    @Autowired
    private Map<String, DataSource> dataSources;

    /** Once, after the application migrated the schema: grants for the application role and one tenant user. */
    @BeforeEach
    void seed() throws SQLException {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        alice = KEYCLOAK.createUser("alice");
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            try (PreparedStatement insert = owner.prepareStatement(
                    "insert into public.tenant (id, code, status, time_zone) values (?::uuid, 'b33-tenant', 'ACTIVE', 'Etc/UTC')")) {
                insert.setString(1, TENANT.toString());
                insert.executeUpdate();
            }
            UUID userId = UUID.randomUUID();
            try (PreparedStatement insert = owner.prepareStatement("insert into public.app_user"
                    + " (id, tenant_id, issuer, subject, status) values (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
                insert.setString(1, userId.toString());
                insert.setString(2, TENANT.toString());
                insert.setString(3, KEYCLOAK.issuerUri());
                insert.setString(4, alice.subject());
                insert.executeUpdate();
            }
            try (PreparedStatement grant = owner.prepareStatement("insert into public.role_grant (tenant_id, user_id, role)"
                    + " values (?::uuid, ?::uuid, 'TENANT_ADMIN')")) {
                grant.setString(1, TENANT.toString());
                grant.setString(2, userId.toString());
                grant.executeUpdate();
            }
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
    }

    /** The cookie values: label to the exact text a browser would send. */
    private static Map<String, String> tampered() {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("not base64", "x'--");
        cookies.put("plain uuid, not encoded", "0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d");
        cookies.put("unknown but well-formed id", b64("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d"));
        cookies.put("wrong length", b64("abc"));
        cookies.put("sql looking", b64("' OR '1'='1' --"));
        cookies.put("invalid utf-8 bytes", Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe, 'a'}));
        cookies.put("uuid plus a NUL byte", b64("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d\0"));
        cookies.put("NUL in the middle", b64("0b0f6a1e-3c1a-4a52-\0-1a7a0a1b2c3d"));
        String genuineShape = b64("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d");
        cookies.put("S3-A5 appended data (id + AAAA)", genuineShape + "AAAA");
        cookies.put("S3-A5 doubled", genuineShape + genuineShape);
        cookies.put("S3-A5 truncated", genuineShape.substring(0, genuineShape.length() / 2));
        cookies.put("only NUL bytes (4000 x A)", "A".repeat(4000));
        cookies.put("oversized, 6000 bytes", b64("a".repeat(6000)));
        return cookies;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private int activeConnections() {
        int active = 0;
        for (DataSource source : dataSources.values()) {
            try {
                var pool = source.unwrap(HikariDataSource.class).getHikariPoolMXBean();
                active += pool == null ? 0 : pool.getActiveConnections();
            } catch (java.sql.SQLException notAHikariPool) {
                // not the application's pool
            }
        }
        return active;
    }

    private int settledActiveConnections() throws InterruptedException {
        int active = activeConnections();
        for (int i = 0; i < 20 && active > 0; i++) {
            Thread.sleep(100);
            active = activeConnections();
        }
        return active;
    }

    @Test
    @Tag("negative")
    void everyTamperedSessionCookieIs401AndNoConnectionIsLeaked(CapturedOutput output) throws InterruptedException {
        List<String> refused = new ArrayList<>();
        List<String> leaked = new ArrayList<>();
        for (Map.Entry<String, String> variant : tampered().entrySet()) {
            ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
            browser.setCookie("SESSION", variant.getValue());
            int status;
            String body;
            try {
                Exchange answer = browser.api("GET", "/api/v1/me", null, false);
                status = answer.status();
                body = answer.body();
            } catch (RuntimeException unanswered) {
                status = 0;
                body = unanswered.toString();
            }
            int active = settledActiveConnections();
            System.out.println("B33 variant [" + variant.getKey() + "] -> " + status + ", active connections after: "
                    + active);
            // an over-long header is refused by Tomcat itself (400) before the application: no session lookup, no pool use
            boolean containerRefusal = status == 400 && variant.getKey().startsWith("oversized");
            if (status != 401 && !containerRefusal) {
                refused.add(variant.getKey() + " -> " + status + " " + body.replaceAll("\s+", " ").substring(0,
                        Math.min(80, body.replaceAll("\s+", " ").length())));
            }
            if (active > 0) {
                leaked.add(variant.getKey() + " left " + active + " connection(s) active");
            }
        }
        assertThat(refused).as("every tampered cookie is a 401").isEmpty();
        assertThat(leaked).as("no tampered cookie holds a pool connection").isEmpty();
        assertThat(output.getAll()).as("no pool failure, no failed scheduler statement in the log")
                .doesNotContain("Could not open JDBC Connection").doesNotContain("DataIntegrityViolationException");
        assertThatAGenuineLoginStillWorks();
    }

    @Test
    @Tag("negative")
    void aBurstOfTamperedCookiesCannotExhaustThePoolOfTen(CapturedOutput output) throws Exception {
        String nulCookie = "A".repeat(4000);
        for (int i = 0; i < 25; i++) {
            ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
            browser.setCookie("SESSION", nulCookie);
            try {
                browser.api("GET", "/api/v1/me", null, false);
            } catch (RuntimeException ignored) {
                // the next request below is the judge
            }
        }
        ConsoleBrowser after = KEYCLOAK.browser(BACKEND);
        assertThat(after.api("GET", "/api/v1/me", null, false).status())
                .as("the application still answers (401: no session) after 25 tampered requests").isEqualTo(401);
        assertThat(settledActiveConnections()).as("pool connections still in use").isZero();
        // the scheduler (db-scheduler) shares the pool: a plain statement on it answers at once, and nothing failed
        for (DataSource source : dataSources.values()) {
            try (Connection connection = source.getConnection()) {
                assertThat(connection.isValid(2)).isTrue();
            }
        }
        assertThat(output.getAll()).doesNotContain("Could not open JDBC Connection");
        assertThatAGenuineLoginStillWorks();
    }

    private void assertThatAGenuineLoginStillWorks() {
        ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
        assertThat(browser.login(alice).status()).as("a genuine login after the tampering").isEqualTo(302);
        Exchange me = browser.api("GET", "/api/v1/me", null, false);
        assertThat(me.status()).as("the genuine session answers 200").isEqualTo(200);
        assertThat(me.body()).contains(TENANT.toString());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
