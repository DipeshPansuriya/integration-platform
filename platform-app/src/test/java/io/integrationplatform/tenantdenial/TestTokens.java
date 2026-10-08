package io.integrationplatform.tenantdenial;

import io.integrationplatform.identity.ConsoleBrowser;
import io.integrationplatform.identity.ConsoleBrowser.Exchange;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keycloak-issued identities for the denial suite (E4-F1-S4, T-101; ADR-007 Decision 2 conformance): the pinned realm
 * of lane D's {@link KeycloakRealmContainer} (used read-only) issues every token the suite presents - nothing is
 * signed locally any more, and the application validates each token against the realm's real JWKS.
 *
 * <ul>
 *   <li><b>Users</b> log in the way the console does (ADR-007 Decision 1, backend for frontend): the realm's real
 *       login form, then the backend's code callback. The access token stays inside the backend's session; the
 *       suite holds only the session cookie and sends the CSRF header, exactly like the console.</li>
 *   <li><b>Sources</b> are confidential clients with the platform audience scope (ADR-007 Decision 3); each call
 *       carries a fresh client-credentials access token, so no 5-minute token outlives the run.</li>
 * </ul>
 *
 * <p>R07: passwords and client secrets are random per run and held in memory only (no {@code toString} carries one).
 */
final class TestTokens {

    /** The console's session cookie (Spring Session) and its CSRF cookie / header pair (Spring Security, spa()). */
    static final String SESSION_COOKIE = "SESSION";
    static final String CSRF_COOKIE = "XSRF-TOKEN";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final KeycloakRealmContainer keycloak;
    private final URI backend;

    TestTokens(KeycloakRealmContainer keycloak, URI backend) {
        this.keycloak = keycloak;
        this.backend = backend;
    }

    /** A realm user; with {@code tenantClaim} its console token carries {@code tenant=<value>} (a forged claim). */
    RealmUser user(String username, String tenantClaim) {
        return tenantClaim == null ? keycloak.createUser(username) : keycloak.createUser(username,
                Map.of("tenant", tenantClaim));
    }

    /** A source system's confidential client that keeps the realm's default audience scope. */
    ClientCredentials source(String clientId) {
        return keycloak.createConfidentialClient(clientId, true);
    }

    /** A fresh access token of the source's client-credentials grant. */
    String sourceToken(ClientCredentials source) {
        return keycloak.clientCredentialsToken(source.clientId(), source.secret());
    }

    /** Logs the user in through the realm's login form and the backend's callback, as the console does. */
    ConsoleSession login(RealmUser user) {
        ConsoleBrowser browser = keycloak.browser(backend);
        Exchange last = browser.login(user);
        if (last.status() != 302 || !"/login/oauth2/code/keycloak".equals(last.uri().getPath())
                || browser.cookie(SESSION_COOKIE).isEmpty()) {
            throw new IllegalStateException("console login of " + user + " did not end in a backend session: "
                    + last.status() + " " + last.uri().getPath());
        }
        // One read so the backend renders the CSRF cookie for this session (the console does the same on start).
        browser.get("/api/v1/me");
        return new ConsoleSession(browser);
    }

    /**
     * A logged-in console session: the suite's own requests carry its cookies and the CSRF header, so the forgeries
     * (headers, query parameters, bodies) can be added exactly as on the bearer channel.
     */
    static final class ConsoleSession {

        private final ConsoleBrowser browser;

        private ConsoleSession(ConsoleBrowser browser) {
            this.browser = browser;
        }

        void authenticate(HttpRequest.Builder request) {
            List<String> cookies = new ArrayList<>();
            browser.cookie(SESSION_COOKIE).ifPresent(value -> cookies.add(SESSION_COOKIE + "=" + value));
            browser.cookie(CSRF_COOKIE).ifPresent(value -> {
                cookies.add(CSRF_COOKIE + "=" + value);
                request.header(CSRF_HEADER, value);
            });
            request.header("Cookie", String.join("; ", cookies));
        }

        /** Keeps the session's cookies current when the backend renews one. */
        void remember(HttpResponse<?> response) {
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).strip();
                String value = pair.substring(eq + 1).strip();
                if (!value.isEmpty() && (name.equals(SESSION_COOKIE) || name.equals(CSRF_COOKIE))) {
                    browser.setCookie(name, value);
                }
            }
        }
    }
}
