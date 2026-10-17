package io.integrationplatform.routing.templates;

/**
 * Row 278 B22 (Kavita M1/L1): the only text a route refusal echoes from a configuration row - into the 400 answer
 * and into the log-bound detail. No dependency on the exception it feeds (C2 may move that exception's mapping).
 */
final class RefusalText {

    private RefusalText() {
    }

    /** RED stub (row 278 B22): pass-through, the pre-fix behaviour; GREEN bounds it. */
    static String scheme(String scheme) {
        return scheme;
    }

    /** RED stub (row 278 B22): pass-through, the pre-fix behaviour; GREEN bounds it. */
    static String targetType(String targetType) {
        return targetType;
    }
}
