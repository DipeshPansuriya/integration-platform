package io.integrationplatform.config.destination;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Row 510: the name of the request header an API_KEY endpoint puts its key in is tenant-typed text that ends up on the
 * wire verbatim. It must be an RFC 9110 field-name token (no CR, LF, space, colon or other separator, so it can never
 * split a request) and must not replace a header the platform itself sets or that frames the request. Shared by the
 * save (400) and the send (PERMANENT), so the two cannot drift.
 */
public final class ApiKeyHeaderName {

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");

    /** Set by the platform or framing the request; an API key must never overwrite one of them (lower case). */
    private static final Set<String> RESERVED = Set.of("idempotency-key", "content-type", "content-length", "host",
            "connection", "transfer-encoding", "te", "trailer", "upgrade", "expect", "keep-alive", "traceparent",
            "tracestate", "authorization", "proxy-authorization", "proxy-connection", "cookie");

    private ApiKeyHeaderName() {
    }

    /** The reason a name is refused, or empty when it is usable. The text never repeats the name. */
    public static Optional<String> problem(String name) {
        if (name == null || !TOKEN.matcher(name).matches()) {
            return Optional.of("the API key header name must be 1-64 letters, digits or - . _ ~ (no spaces, colons or"
                    + " line breaks)");
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            return Optional.of("the API key header name is reserved (the platform or the request framing sets it;"
                    + " use BASIC or BEARER_TOKEN for Authorization)");
        }
        return Optional.empty();
    }

    /** True when the text holds a CR, LF or NUL - never allowed in a header value. */
    public static boolean hasLineBreak(CharSequence value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }
}
