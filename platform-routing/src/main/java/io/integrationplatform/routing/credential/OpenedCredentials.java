package io.integrationplatform.routing.credential;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A target endpoint's credential values, opened at send time and held in memory for ONE call (ADR-007 Decision 7 rule
 * 6): use it in try-with-resources - {@link #close()} wipes every value. Never cached, never logged:
 * {@link #toString()} names the fields only.
 */
public final class OpenedCredentials implements AutoCloseable {

    private final Map<String, char[]> values;

    OpenedCredentials(Map<String, char[]> values) {
        this.values = new LinkedHashMap<>(values);
    }

    /** The register names of the opened fields ({@code BASIC_PASSWORD}, ...). */
    public Set<String> fields() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /** The value of one field - the held array itself: read it for the call, never keep it. */
    public Optional<char[]> value(String field) {
        return Optional.ofNullable(values.get(field));
    }

    @Override
    public void close() {
        values.values().forEach(value -> Arrays.fill(value, '\0'));
    }

    @Override
    public String toString() {
        return "OpenedCredentials" + values.keySet();
    }
}
