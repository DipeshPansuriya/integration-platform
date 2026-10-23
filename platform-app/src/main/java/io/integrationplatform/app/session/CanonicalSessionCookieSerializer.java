package io.integrationplatform.app.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.session.web.http.CookieSerializer;

/**
 * B33: a session id that is not a canonical UUID never reaches the session store. The store (Spring Session JDBC)
 * generates UUIDs only, so anything else in the cookie is tampering. Before this, a cookie that decoded to an id with a
 * NUL byte made PostgreSQL reject the lookup (SQLSTATE 22021), the request answered 500, the error dispatch repeated the
 * lookup and an unauthenticated caller drained the connection pool. Now such a cookie is "no session": the API answers
 * its 401. Only the shape is checked; whether the session exists is still the store's answer. Writing is untouched, so
 * the cookie name and flags stay the ones {@code server.servlet.session.cookie.*} configures.
 */
final class CanonicalSessionCookieSerializer implements CookieSerializer {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final CookieSerializer delegate;

    CanonicalSessionCookieSerializer(CookieSerializer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        delegate.writeCookieValue(cookieValue);
    }

    @Override
    public List<String> readCookieValues(HttpServletRequest request) {
        return delegate.readCookieValues(request).stream()
                .filter(id -> id != null && CANONICAL_UUID.matcher(id).matches())
                .toList();
    }
}
