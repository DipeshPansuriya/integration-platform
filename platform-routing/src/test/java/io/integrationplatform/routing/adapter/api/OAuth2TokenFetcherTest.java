package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.routing.adapter.api.HttpSend.Response;
import io.integrationplatform.routing.adapter.api.OAuth2TokenFetcher.OAuth2TokenException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Row 510: the token exchange dials only what the guard pinned (through the {@link HttpSend} seam), and its failure
 * text is FIXED per class - a closed port, a 404, a 500 and a body without a token read the same, so the delivery fault
 * a tenant can read is no probe of what a token URL reaches. No host, port or status appears in a message.
 */
class OAuth2TokenFetcherTest {

    private static final CheckedDestination DESTINATION = destination();

    private static CheckedDestination destination() {
        try {
            return new CheckedDestination(URI.create("https://auth.partner.example:8443/oauth/token"),
                    "auth.partner.example", 8443, true, InetAddress.getByName("203.0.113.10"));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OAuth2TokenFetcher answering(int status, String body) {
        return new OAuth2TokenFetcher((destination, method, headers, bytes, timeoutMs) ->
                new Response(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static OAuth2TokenFetcher failing(IOException failure) {
        return new OAuth2TokenFetcher((destination, method, headers, bytes, timeoutMs) -> {
            throw failure;
        });
    }

    @Test
    @DisplayName("a 200 with an access_token returns it; the request is a form POST to the pinned destination")
    void issuesTheToken() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        OAuth2TokenFetcher fetcher = new OAuth2TokenFetcher((destination, method, headers, bytes, timeoutMs) -> {
            assertThat(destination).isSameAs(DESTINATION);
            assertThat(method).isEqualTo("POST");
            assertThat(headers).containsEntry("Content-Type", "application/x-www-form-urlencoded");
            seen.set(new String(bytes, StandardCharsets.UTF_8));
            return new Response(200, "{\"access_token\":\"abc\"}".getBytes(StandardCharsets.UTF_8));
        });

        assertThat(fetcher.fetchToken(DESTINATION, "my id", "s&cret".toCharArray())).isEqualTo("abc");
        assertThat(seen.get()).isEqualTo("grant_type=client_credentials&client_id=my+id&client_secret=s%26cret");
    }

    @Test
    @Tag("security")
    @DisplayName("a closed port, a 404, a 302, a 500 and a body without a token all read the same - no reachability oracle")
    void failuresReadTheSame() {
        String closedPort = messageOf(failing(new ConnectException("Connection refused: connect")));
        String notFound = messageOf(answering(404, ""));
        String redirect = messageOf(answering(302, ""));
        String server = messageOf(answering(500, ""));
        String noToken = messageOf(answering(200, "{\"error\":\"nope\"}"));
        String notJson = messageOf(answering(200, "<html>"));

        assertThat(closedPort).isEqualTo(OAuth2TokenFetcher.NO_TOKEN);
        assertThat(notFound).isEqualTo(closedPort);
        assertThat(redirect).isEqualTo(closedPort);
        assertThat(server).isEqualTo(closedPort);
        assertThat(noToken).isEqualTo(closedPort);
        assertThat(notJson).isEqualTo(closedPort);
        assertThat(closedPort).doesNotContain("auth.partner.example").doesNotContain("8443").doesNotContain("404")
                .doesNotContain("refused").doesNotContain("HTTP");
    }

    @Test
    @DisplayName("401 and 403 are PERMANENT 'rejected the credentials'; network failures and 5xx are TRANSIENT; other"
            + " statuses and a missing token are PERMANENT")
    void classes() {
        assertThatThrownBy(() -> answering(401, "").fetchToken(DESTINATION, "c", "s".toCharArray()))
                .isInstanceOfSatisfying(OAuth2TokenException.class, e -> {
                    assertThat(e.isTransient()).isFalse();
                    assertThat(e.getMessage()).isEqualTo(OAuth2TokenFetcher.REJECTED);
                });
        assertThatThrownBy(() -> answering(403, "").fetchToken(DESTINATION, "c", "s".toCharArray()))
                .isInstanceOfSatisfying(OAuth2TokenException.class, e -> assertThat(e.isTransient()).isFalse());
        assertThatThrownBy(() -> failing(new IOException("reset")).fetchToken(DESTINATION, "c", "s".toCharArray()))
                .isInstanceOfSatisfying(OAuth2TokenException.class, e -> assertThat(e.isTransient()).isTrue());
        assertThatThrownBy(() -> answering(503, "").fetchToken(DESTINATION, "c", "s".toCharArray()))
                .isInstanceOfSatisfying(OAuth2TokenException.class, e -> assertThat(e.isTransient()).isTrue());
        assertThatThrownBy(() -> answering(404, "").fetchToken(DESTINATION, "c", "s".toCharArray()))
                .isInstanceOfSatisfying(OAuth2TokenException.class, e -> assertThat(e.isTransient()).isFalse());
        assertThatThrownBy(() -> answering(200, "{}").fetchToken(DESTINATION, "c", "s".toCharArray()))
                .isInstanceOfSatisfying(OAuth2TokenException.class, e -> assertThat(e.isTransient()).isFalse());
    }

    private static String messageOf(OAuth2TokenFetcher fetcher) {
        try {
            fetcher.fetchToken(DESTINATION, "c", "s".toCharArray());
            throw new AssertionError("a token was issued");
        } catch (OAuth2TokenException failure) {
            return failure.getMessage();
        }
    }
}
