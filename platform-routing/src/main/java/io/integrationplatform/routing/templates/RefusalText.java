package io.integrationplatform.routing.templates;

import java.util.regex.Pattern;

/**
 * Row 278 B22 (Kavita M1/L1, CWE-209/79/117/532): the only text a route refusal echoes from a configuration row -
 * into the 400 answer and into the log-bound detail. A value that is not a URI scheme or a short identifier becomes
 * a fixed text, so userinfo, a token or CR/LF never leaves through a refusal. No dependency on the exception it
 * feeds (C2 may move that exception's 400 mapping).
 */
final class RefusalText {

    static final String NOT_A_SCHEME = "(not a URI scheme)";
    static final String NOT_A_TYPE = "(not a target type)";

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]{0,31}");
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,31}");

    private RefusalText() {
    }

    /** The scheme if it is one ({@code ^[a-z][a-z0-9+.-]{0,31}$}), else {@value #NOT_A_SCHEME}. */
    static String scheme(String scheme) {
        return scheme != null && SCHEME.matcher(scheme).matches() ? scheme : NOT_A_SCHEME;
    }

    /** The target type if it is a short identifier ({@code ^[A-Za-z][A-Za-z0-9_.-]{0,31}$}), else {@value #NOT_A_TYPE}. */
    static String targetType(String targetType) {
        return targetType != null && TYPE.matcher(targetType).matches() ? targetType : NOT_A_TYPE;
    }
}
