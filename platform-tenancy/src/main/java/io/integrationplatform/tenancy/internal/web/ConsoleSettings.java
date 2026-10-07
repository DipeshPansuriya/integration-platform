package io.integrationplatform.tenancy.internal.web;

import java.util.Objects;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * E4 (T-097), ADR-007 Decision 1: the console's OIDC client ({@code platform.tenancy.console.*} in tenancy-console.yml,
 * every value an environment variable name - R07). The issuer is the resource server's ({@code OIDC_ISSUER_URI}): one
 * realm for API tokens and console logins.
 *
 * @param clientId              the confidential console client ({@code CONSOLE_OIDC_CLIENT_ID})
 * @param clientSecret          its secret ({@code CONSOLE_OIDC_CLIENT_SECRET}); held in memory, never logged
 * @param redirectUri           the backend's {@code /login/oauth2/code/keycloak} as the browser reaches it
 * @param postLoginUri          where the browser goes after a login (the console entry)
 * @param postLogoutRedirectUri where the identity provider sends the browser after the sign-out
 * @param sessionCookieName     the session cookie that routes a token-less {@code /api/**} call to the console chain
 */
record ConsoleSettings(String clientId, String clientSecret, String redirectUri, String postLoginUri,
        String postLogoutRedirectUri, String sessionCookieName) {

    static final String PREFIX = "platform.tenancy.console";
    static final String REGISTRATION_ID = "keycloak";

    ConsoleSettings {
        requireText(clientId, "client-id");
        requireText(clientSecret, "client-secret");
        requireText(redirectUri, "redirect-uri");
        requireText(postLoginUri, "post-login-uri");
        requireText(postLogoutRedirectUri, "post-logout-redirect-uri");
        requireText(sessionCookieName, "session cookie name");
    }

    /** Resolves strictly: an unset variable named in tenancy-console.yml fails the start-up and names the variable. */
    static ConsoleSettings from(Environment environment) {
        Binder binder = Binder.get(environment);
        return new ConsoleSettings(
                environment.getRequiredProperty(PREFIX + ".client-id"),
                environment.getRequiredProperty(PREFIX + ".client-secret"),
                environment.getRequiredProperty(PREFIX + ".redirect-uri"),
                environment.getRequiredProperty(PREFIX + ".post-login-uri"),
                environment.getRequiredProperty(PREFIX + ".post-logout-redirect-uri"),
                binder.bind("server.servlet.session.cookie.name", String.class).orElse("SESSION"));
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalStateException(PREFIX + "." + name + " is blank");
        }
    }

    @Override
    public String toString() { // R07: the secret never reaches a log line
        return "ConsoleSettings[clientId=" + clientId + ", redirectUri=" + redirectUri + "]";
    }
}
