package io.integrationplatform.e2e.release1;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Row 323 T140: the black-box calls the scenarios make, stdlib HttpClient only. The backend is
 * {@link E2eNames#baseUrl()} (loopback only). Bearer tokens come from the environment the T143 compose profile sets
 * ({@code E2E_BEARER_<ROLE>}); no token is ever written in a file.
 */
public final class Release1Client {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern TRACKING_ID = Pattern.compile("\"trackingId\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;

    public Release1Client(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static String bearer(String role) {
        String value = System.getenv("E2E_BEARER_" + role);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("E2E_BEARER_" + role + " is not set - waits on T143 (compose e2e profile)");
        }
        return value;
    }

    /** POST to the one intake endpoint (IntakeController.java:53) with its three required headers. */
    public HttpResponse<String> intake(String interfaceCode, String contractVersion, String json, String bearer)
            throws IOException, InterruptedException {
        String path = E2eNames.nameOf("intakeApi").replace("{interfaceCode}", interfaceCode);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", contractVersion)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** POST to the bulk intake endpoint (IntakeController.submitBulk): the same three headers, a JSON array body. */
    public HttpResponse<String> bulk(String interfaceCode, String contractVersion, String jsonArray, String bearer)
            throws IOException, InterruptedException {
        String path = E2eNames.nameOf("bulkIntakeApi").replace("{interfaceCode}", interfaceCode);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", contractVersion)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(jsonArray, StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The WireMock journal reads back as JSON whose request bodies are ESCAPED strings, so a needle such as
     * {@code "contractVersion":"1.0"} never matches the raw text. This undoes one level of JSON string escaping
     * (the result is no longer valid JSON; it is for substring and ordering checks only).
     */
    public static String journalText(String journalJson) {
        return journalJson.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String path, String json, String bearer) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** WireMock request journal ({@code /__admin/requests}) - what target A actually received. */
    public HttpResponse<String> wiremockJournal(String wiremockBase) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(wiremockBase + "/__admin/requests")).timeout(TIMEOUT)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> wiremockStub(String wiremockBase, String mappingJson)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(wiremockBase + "/__admin/mappings")).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mappingJson, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public void wiremockReset(String wiremockBase) throws IOException, InterruptedException {
        http.send(HttpRequest.newBuilder(URI.create(wiremockBase + "/__admin/mappings/reset")).timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
    }

    public static String trackingIdOf(String body) {
        Matcher matcher = TRACKING_ID.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no trackingId in intake response: " + body);
        }
        return matcher.group(1);
    }

    public static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
