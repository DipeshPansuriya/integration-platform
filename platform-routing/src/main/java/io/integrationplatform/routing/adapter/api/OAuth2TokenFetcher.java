package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.CheckedDestination;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches an OAuth2 access token using the client_credentials grant. One fresh token per call (ADR-007: credentials
 * are never cached); a high-volume deployment should add a short-lived cache keyed by target endpoint id.
 *
 * <p>The token endpoint URL comes from {@code target_endpoint.auth_token_url} (V2_4_1430); the client_id from
 * {@code auth_identity}; the client_secret from the opened {@code OAUTH_CLIENT_SECRET} credential field. The
 * exchange uses form-encoded POST with the credentials in the body (RFC 6749 section 4.4).
 *
 * <p>Row 510: the fetcher never resolves or chooses an address. The caller passes the {@link CheckedDestination} the
 * {@code DestinationGuard} pinned for the token URL, and the request leaves through the same {@link HttpSend} seam as
 * the delivery itself: it dials that one address, never follows a redirect, requires TLS unless the guard lifted it for
 * the showcase host, and reads a bounded answer. Failures carry a FIXED text per class - no host, port, status or JDK
 * message - so the delivery fault a tenant reads cannot be used to probe what a token URL reaches; the detail goes to
 * the server log without the host.
 */
public final class OAuth2TokenFetcher {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenFetcher.class);
    /** The token exchange's whole-call timeout (connect and read). */
    static final int TIMEOUT_MS = 30_000;

    static final String REJECTED = "the token endpoint rejected the credentials";
    static final String NO_TOKEN = "the token endpoint did not issue a token";

    private final HttpSend sender;

    public OAuth2TokenFetcher(HttpSend sender) {
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
    }

    /**
     * Exchanges client credentials for an access token.
     *
     * @param tokenEndpoint the destination the guard checked and pinned for the token URL
     * @return the access token string
     * @throws OAuth2TokenException on any failure (network, bad status, missing token in response)
     */
    public String fetchToken(CheckedDestination tokenEndpoint, String clientId, char[] clientSecret)
            throws OAuth2TokenException {
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(new String(clientSecret), StandardCharsets.UTF_8);

        HttpSend.Response response;
        try {
            response = sender.send(tokenEndpoint, "POST",
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                    body.getBytes(StandardCharsets.UTF_8), TIMEOUT_MS);
        } catch (IOException networkFailed) {
            log.warn("OAuth2 token exchange failed before an answer ({})", networkFailed.getClass().getSimpleName());
            throw new OAuth2TokenException(true, NO_TOKEN);
        }

        if (response.status() == 401 || response.status() == 403) {
            throw new OAuth2TokenException(false, REJECTED);
        }
        if (response.status() != 200) {
            log.warn("OAuth2 token exchange answered HTTP {}", response.status());
            throw new OAuth2TokenException(response.status() >= 500, NO_TOKEN);
        }

        String accessToken = extractAccessToken(new String(response.body(), StandardCharsets.UTF_8));
        if (accessToken == null || accessToken.isBlank()) {
            throw new OAuth2TokenException(false, NO_TOKEN);
        }
        log.debug("OAuth2 client_credentials token obtained");
        return accessToken;
    }

    private static String extractAccessToken(String json) {
        try {
            tools.jackson.databind.JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(json);
            tools.jackson.databind.JsonNode tokenNode = node.get("access_token");
            return tokenNode != null ? tokenNode.asString() : null;
        } catch (Exception parseError) {
            return null;
        }
    }

    /**
     * A classified token-exchange failure.
     *
     * @param transient_ true when the failure is transient (network, 5xx) and the delivery should retry;
     *                   false when permanent (401, 403, missing token — the configuration is wrong)
     */
    public static final class OAuth2TokenException extends Exception {

        private final boolean transient_;

        public OAuth2TokenException(boolean transient_, String message) {
            super(message);
            this.transient_ = transient_;
        }

        public boolean isTransient() {
            return transient_;
        }
    }
}
