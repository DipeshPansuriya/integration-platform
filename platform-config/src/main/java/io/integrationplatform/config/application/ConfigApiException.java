package io.integrationplatform.config.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A refused API call with the HTTP answer the scenario names (T-015/T-019/T-021). The exception exists so every
 * non-2xx leaves the module as an explicit {@code ResponseEntity} through {@code ConfigApiExceptionHandler}:
 * a propagated error would be forwarded to {@code /error}, which the stateless security chain denies (403 with an
 * empty body), turning a designed 404/409/500 into an accidental 403.
 *
 * <p>The body is the JSON the answer carries: {@code {"error": ...}} for a refusal, {@code {"refusal": <code>}}
 * where the scenario names the refusal (the recorded approval refusals, ADR-011 / BR-04).
 */
public class ConfigApiException extends RuntimeException {

    private final int httpStatus;
    private final Map<String, Object> body;

    protected ConfigApiException(int httpStatus, Map<String, Object> body) {
        super("the configuration API refused the call with " + httpStatus);
        this.httpStatus = httpStatus;
        this.body = Objects.requireNonNull(body, "body");
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> body() {
        return body;
    }

    /** 400 with a clear validation message naming the refused field or value. */
    public static ConfigApiException validation(String message) {
        return error(400, message);
    }

    /** 404 - the row is not visible to this tenant; the body never carries a hint of whose row it is. */
    public static ConfigApiException notFound() {
        return error(404, "not found");
    }

    /** 409 - the row exists but the call contradicts its state. */
    public static ConfigApiException conflict(String message) {
        return error(409, message);
    }

    /** 500 - a write failed mid-transaction; the static text carries no row data (the rollback is the answer). */
    public static ConfigApiException server(String message) {
        return error(500, message);
    }

    /** 403 with the named refusal code (the approval invariant's answer). */
    public static ConfigApiException refusal(int httpStatus, String refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refusal", Objects.requireNonNull(refusal, "refusal"));
        return new ConfigApiException(httpStatus, body);
    }

    private static ConfigApiException error(int httpStatus, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return new ConfigApiException(httpStatus, body);
    }

    /** Builds the ordered key/value map used for every response body. */
    public static Map<String, Object> body(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("body() needs an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }
}
