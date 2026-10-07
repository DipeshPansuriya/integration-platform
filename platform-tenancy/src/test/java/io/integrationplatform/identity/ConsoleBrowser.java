package io.integrationplatform.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal browser for the console login in tests (E4, T-097): one cookie jar per host and port, redirects followed
 * by hand, the Keycloak login form filled and posted. It keeps every exchange so a test can inspect each cookie and
 * body the backend ever sent (ADR-007 Decision 1: no access token reaches the browser).
 *
 * <p>Cookies are sent whatever their {@code Secure} flag (the JDK cookie manager would drop Secure cookies over the
 * plain-HTTP loopback the tests use; a browser treats loopback as a secure context).
 */
public final class ConsoleBrowser {

    /** One request and its answer, unchanged. */
    public record Exchange(String method, URI uri, int status, Map<String, List<String>> headers, String body) {

        public Optional<String> location() {
            return headers.getOrDefault("location", List.of()).stream().findFirst();
        }

        public List<String> setCookies() {
            return headers.getOrDefault("set-cookie", List.of());
        }

        public boolean isRedirect() {
            return status >= 300 && status < 400 && location().isPresent();
        }
    }

    private static final Pattern LOGIN_FORM = Pattern.compile("<form[^>]*\\bid=\"kc-form-login\"[^>]*>");
    private static final Pattern ACTION = Pattern.compile("\\baction=\"([^\"]+)\"");

    private final URI backend;
    private final HttpClient http;
    private final Map<String, Map<String, String>> jars = new HashMap<>();
    private final List<Exchange> history = new ArrayList<>();

    ConsoleBrowser(URI backend, HttpClient http) {
        this.backend = backend;
        this.http = http;
    }

    /**
     * Opens the backend's login entry, follows it to the realm's login form, posts the credentials and follows the
     * code callback back to the backend. Returns the last exchange: on success the backend's redirect to the console
     * entry (a {@code /} that the backend itself does not serve); on a refused login the realm's form page again.
     */
    public Exchange login(String username, String password) {
        Exchange page = followFrom(send("GET", backend.resolve("/oauth2/authorization/keycloak"), Map.of(), null));
        if (page.status() != 200) {
            return page;
        }
        Matcher form = LOGIN_FORM.matcher(page.body());
        if (!form.find()) {
            return page;
        }
        Matcher action = ACTION.matcher(form.group());
        if (!action.find()) {
            throw new IllegalStateException("the realm's login form has no action");
        }
        URI post = page.uri().resolve(action.group(1).replace("&amp;", "&"));
        Exchange posted = send("POST", post, Map.of("Content-Type", "application/x-www-form-urlencoded"),
                KeycloakRealmContainer.formBody(Map.of("username", username, "password", password, "credentialId", "")));
        return followFrom(posted);
    }

    public Exchange login(KeycloakRealmContainer.RealmUser user) {
        return login(user.username(), user.password());
    }

    /** A console call to the backend: session cookie only, and the CSRF header when {@code withCsrfHeader}. */
    public Exchange api(String method, String path, String jsonBody, boolean withCsrfHeader) {
        Map<String, String> headers = new HashMap<>();
        if (jsonBody != null) {
            headers.put("Content-Type", "application/json");
        }
        if (withCsrfHeader) {
            cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        }
        return send(method, backend.resolve(path), headers, jsonBody);
    }

    /** A GET of a backend path, redirects not followed. */
    public Exchange get(String path) {
        return send("GET", backend.resolve(path), Map.of(), null);
    }

    /** The backend's current value of a cookie. */
    public Optional<String> cookie(String name) {
        return Optional.ofNullable(jars.getOrDefault(authority(backend), Map.of()).get(name));
    }

    /** Puts a cookie of the backend into the jar, as a browser holding it would send it (for example a dead session). */
    public void setCookie(String name, String value) {
        jars.computeIfAbsent(authority(backend), k -> new HashMap<>()).put(name, value);
    }

    /** Forgets every cookie of the backend (a browser with no session). */
    public void clearBackendCookies() {
        jars.remove(authority(backend));
    }

    /** Every exchange so far, in order. */
    public List<Exchange> history() {
        return List.copyOf(history);
    }

    /** Every exchange with the backend (not with Keycloak). */
    public List<Exchange> backendHistory() {
        return history.stream().filter(e -> authority(e.uri()).equals(authority(backend))).toList();
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Follows redirects; stops at a page, or at a redirect to a backend path the backend itself does not serve. */
    private Exchange followFrom(Exchange start) {
        Exchange current = start;
        for (int hops = 0; hops < 12 && current.isRedirect(); hops++) {
            URI next = current.uri().resolve(current.location().orElseThrow());
            if (authority(next).equals(authority(backend)) && !isBackendLoginPath(next.getPath())) {
                return current;
            }
            current = send("GET", next, Map.of(), null);
        }
        return current;
    }

    private static boolean isBackendLoginPath(String path) {
        return path.startsWith("/oauth2/") || path.startsWith("/login/");
    }

    private Exchange send(String method, URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
        headers.forEach(request::header);
        Map<String, String> jar = jars.getOrDefault(authority(uri), Map.of());
        if (!jar.isEmpty()) {
            request.header("Cookie", String.join("; ",
                    jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + uri.getPath() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        Map<String, List<String>> lowerCased = new HashMap<>();
        response.headers().map().forEach((k, v) -> lowerCased.put(k.toLowerCase(Locale.ROOT), v));
        Exchange exchange = new Exchange(method, uri, response.statusCode(), lowerCased, response.body());
        remember(uri, exchange.setCookies());
        history.add(exchange);
        return exchange;
    }

    private void remember(URI uri, List<String> setCookies) {
        Map<String, String> jar = jars.computeIfAbsent(authority(uri), k -> new HashMap<>());
        for (String header : setCookies) {
            String[] parts = header.split(";");
            int eq = parts[0].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = parts[0].substring(0, eq).strip();
            String value = parts[0].substring(eq + 1).strip();
            boolean expired = value.isEmpty();
            for (int i = 1; i < parts.length; i++) {
                String attribute = parts[i].strip().toLowerCase(Locale.ROOT);
                if (attribute.equals("max-age=0") || attribute.startsWith("expires=thu, 01 jan 1970")
                        || attribute.startsWith("expires=thu, 01-jan-1970")) {
                    expired = true;
                }
            }
            if (expired) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    private static String authority(URI uri) {
        return uri.getHost() + ":" + uri.getPort();
    }
}
