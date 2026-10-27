package io.integrationplatform.routing.adapter.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches an OAuth2 access token using the client_credentials grant. One fresh token per call (ADR-007: credentials
 * are never cached); a high-volume deployment should add a short-lived cache keyed by target endpoint id.
 *
 * <p>The token endpoint URL comes from {@code target_endpoint.auth_token_url} (V2_4_1420); the client_id from
 * {@code auth_identity}; the client_secret from the opened {@code OAUTH_CLIENT_SECRET} credential field. The
 * exchange uses form-encoded POST with the credentials in the body (RFC 6749 section 4.4).
 */
public final class OAuth2TokenFetcher {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenFetcher.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public OAuth2TokenFetcher() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    OAuth2TokenFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Exchanges client credentials for an access token.
     *
     * @return the access token string
     * @throws OAuth2TokenException on any failure (network, bad status, missing token in response)
     */
    public String fetchToken(String tokenUrl, String clientId, char[] clientSecret) throws OAuth2TokenException {
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(new String(clientSecret), StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException networkFailed) {
            throw new OAuth2TokenException(true, "the token endpoint at " + redactedOrigin(tokenUrl)
                    + " is unreachable: " + networkFailed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OAuth2TokenException(true, "the token request was interrupted");
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new OAuth2TokenException(false, "the token endpoint rejected the credentials (HTTP "
                    + response.statusCode() + ")");
        }
        if (response.statusCode() != 200) {
            boolean transient_ = response.statusCode() >= 500;
            throw new OAuth2TokenException(transient_, "the token endpoint returned HTTP " + response.statusCode());
        }

        String accessToken = extractAccessToken(response.body());
        if (accessToken == null || accessToken.isBlank()) {
            throw new OAuth2TokenException(false, "the token response contains no access_token");
        }
        log.debug("OAuth2 client_credentials token obtained from {}", redactedOrigin(tokenUrl));
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

    private static String redactedOrigin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (Exception ignored) {
            return "(malformed)";
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
