package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.internal.web.ConsoleClientRegistrations.IdentityProviderUnavailableException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Row 571 follow-up 5: a failed code exchange (cancel/denied, an expired or doubled login form, a state mismatch) on
 * {@code /login/oauth2/code/keycloak?code=...&state=...} used to end on raw JSON. A browser navigation is now sent to
 * the console's sign-in state with a short error flag (the loop guard there shows "Try again"); API clients keep JSON.
 */
class ConsoleLoginFailureAnswerTest {

    private static final String BROWSER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private static MockHttpServletRequest callback(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");
        request.setQueryString("code=single-use-code&state=abc");
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    private static MockHttpServletResponse answer(MockHttpServletRequest request, AuthenticationException failure)
            throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ConsoleSecurityConfiguration.loginFailed(request, response, failure);
        return response;
    }

    @Test
    void aBrowserNavigationIsSentToTheSignInStateWithAShortFlag() throws IOException {
        MockHttpServletResponse response = answer(callback(BROWSER), new OAuth2AuthenticationException("access_denied"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/#/login?error=login_failed");
        assertThat(response.getContentAsString()).as("no JSON dead end").isEmpty();
        assertThat(response.getRedirectedUrl()).as("the single-use code and state are not carried over")
                .doesNotContain("code").doesNotContain("state");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void anUnreachableProviderIsFlaggedAsUnavailableForABrowser() throws IOException {
        MockHttpServletResponse response = answer(callback(BROWSER),
                new OAuth2AuthenticationException(new OAuth2Error("server_error"), new IdentityProviderUnavailableException(new IOException("down"))));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/#/login?error=idp_unavailable");
    }

    @Test
    void theRedirectKeepsTheContextPath() throws IOException {
        MockHttpServletRequest request = callback(BROWSER);
        request.setContextPath("/console");

        assertThat(answer(request, new BadCredentialsException("x")).getRedirectedUrl())
                .isEqualTo("/console/#/login?error=login_failed");
    }

    @Test
    void anApiClientKeepsTheJsonAnswer() throws IOException {
        for (String accept : new String[] {null, "application/json", "*/*"}) {
            MockHttpServletResponse failed = answer(callback(accept), new BadCredentialsException("x"));
            assertThat(failed.getStatus()).as("Accept: " + accept).isEqualTo(401);
            assertThat(failed.getContentType()).startsWith("application/json");
            assertThat(failed.getContentAsString()).contains("login_failed");
            assertThat(failed.getRedirectedUrl()).isNull();
        }
        MockHttpServletResponse down = answer(callback("application/json"),
                new OAuth2AuthenticationException(new OAuth2Error("server_error"), new IdentityProviderUnavailableException(new IOException("down"))));
        assertThat(down.getStatus()).isEqualTo(503);
        assertThat(down.getContentAsString()).contains("identity_provider_unavailable");
    }

    @Test
    void aBrowserFetchOfTheApiStillGetsJson() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Accept", BROWSER);

        MockHttpServletResponse response = answer(request, new BadCredentialsException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
    }
}
