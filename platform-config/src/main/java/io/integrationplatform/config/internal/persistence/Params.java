package io.integrationplatform.config.internal.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/** The named-parameter map builder shared by the module's repositories (values null-tolerant, order kept). */
final class Params {

    private Params() {
    }

    static Map<String, Object> of(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("of() needs an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }
}
