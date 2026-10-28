package io.integrationplatform.routing.credential;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test seam (same package as the package-private constructor): opened credentials from plain strings. */
public final class TestOpenedCredentials {

    private TestOpenedCredentials() {
    }

    public static OpenedCredentials of(Map<String, String> values) {
        Map<String, char[]> chars = new LinkedHashMap<>();
        values.forEach((field, value) -> chars.put(field, value.toCharArray()));
        return new OpenedCredentials(chars);
    }
}
