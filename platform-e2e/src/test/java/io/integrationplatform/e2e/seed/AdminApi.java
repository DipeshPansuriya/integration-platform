package io.integrationplatform.e2e.seed;

/**
 * Row 327 T157: the platform's admin API as one signed-in console user sees it. The harness owns the session (the
 * realm's login form, the same way the console signs in); the seed only says what to call.
 */
@FunctionalInterface
public interface AdminApi {

    /** Sends {@code method path} with a JSON body (or none) and answers the status and the raw body. */
    Response send(String method, String path, String jsonBody);

    /** One answer of the API. */
    record Response(int status, String body) {
    }
}
