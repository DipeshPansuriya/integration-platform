package io.integrationplatform.routing.stage2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Row 327 (E16-T0): the registry of stage-2 target calls by stored system.type ("MQ", "API" today). Composition
 * registers its handlers here; a later system type (KAFKA_TOPIC, T-148) registers without touching the route wiring.
 * Dispatch itself stays {@link TargetCall#byType} - unknown or missing type is refused PERMANENT there.
 */
public final class TargetCallRegistry {

    private final Map<String, TargetCall> calls = new LinkedHashMap<>();

    public void register(String type, TargetCall call) {
        calls.put(type, call);
    }

    /** An immutable snapshot, the shape {@link TargetCall#byType} consumes. */
    public Map<String, TargetCall> calls() {
        return Map.copyOf(calls);
    }
}