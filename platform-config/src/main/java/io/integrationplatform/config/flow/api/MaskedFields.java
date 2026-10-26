package io.integrationplatform.config.flow.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * T-066 / E12-F1-S2 (row 278 T108): a flow's masked fields - payload paths (dotted, optional {@code $.} prefix)
 * whose values must never appear in a log line or a tracking API answer.
 *
 * <ul>
 *   <li>{@link #parse}/{@link #fromJson}/{@link #toJson}: the list as the flow API takes it and the store keeps it;</li>
 *   <li>{@link #valuesOf}: the scalar values those paths pick out of one payload (an object or array path collects
 *       every scalar leaf under it);</li>
 *   <li>{@link #redact}: one text with those values replaced by {@link #MARKER} (the tracking read, per message);</li>
 *   <li>{@link #register}/{@link #scrub}: a bounded in-process registry intake fills and the JSON log encoder reads,
 *       so a value is scrubbed from any later log line whatever logger wrote it.</li>
 * </ul>
 */
public final class MaskedFields {

    /** What a masked value is replaced with. */
    public static final String MARKER = "[MASKED]";

    /** ponytail: bounded LRU, per node; a restart empties it until intake registers again. */
    private static final int REGISTRY_LIMIT = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(MaskedFields.class);

    /** Evictions since start; the first and every 100th is logged (a count only - never the evicted value). */
    private static final AtomicLong EVICTIONS = new AtomicLong();
    private static final int EVICTION_LOG_EVERY = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Map<String, Boolean> REGISTRY = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    if (size() <= REGISTRY_LIMIT) {
                        return false;
                    }
                    long evicted = EVICTIONS.incrementAndGet();
                    if (evicted == 1 || evicted % EVICTION_LOG_EVERY == 0) {
                        LOG.warn("Masked-value registry full at {} entries: oldest entry evicted ({} evicted since start); "
                                + "an evicted value is no longer scrubbed from later log lines", REGISTRY_LIMIT, evicted);
                    }
                    return true;
                }
            });

    private MaskedFields() {
    }

    /**
     * The {@code maskedFields} member of a flow create/update body: empty when absent or null; the trimmed paths
     * otherwise. Anything but an array of non-blank strings is refused with {@link IllegalArgumentException}.
     */
    public static Optional<List<String>> parse(JsonNode body) {
        JsonNode node = body == null ? null : body.get("maskedFields");
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("maskedFields must be an array of field paths");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : node) {
            if (!entry.isString() || entry.asString().isBlank()) {
                throw new IllegalArgumentException("maskedFields entries must be non-blank field paths");
            }
            paths.add(entry.asString().trim());
        }
        return Optional.of(List.copyOf(paths));
    }

    /** The stored JSON array text as paths; null or blank reads as none. */
    public static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(json)) {
            paths.add(entry.asString());
        }
        return List.copyOf(paths);
    }

    /** The paths as the JSON array text the store keeps. */
    public static String toJson(Collection<String> paths) {
        ArrayNode array = JSON.createArrayNode();
        paths.forEach(array::add);
        return array.toString();
    }

    /** Every scalar value the paths pick out of the payload, as text. */
    public static Set<String> valuesOf(JsonNode payload, Collection<String> paths) {
        Set<String> values = new LinkedHashSet<>();
        if (payload == null || paths == null) {
            return values;
        }
        for (String path : paths) {
            String dotted = path.startsWith("$.") ? path.substring(2) : path;
            collect(payload, List.of(dotted.split("\\.")), 0, values);
        }
        return values;
    }

    private static void collect(JsonNode node, List<String> segments, int index, Set<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collect(element, segments, index, values);
            }
            return;
        }
        if (index == segments.size()) {
            if (node.isObject()) {
                for (JsonNode child : node) {
                    collect(child, segments, index, values);
                }
            } else {
                String text = node.asString();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
            return;
        }
        if (node.isObject()) {
            collect(node.get(segments.get(index)), segments, index + 1, values);
        }
    }

    /** The text with every occurrence of each value replaced by {@link #MARKER} (longest first); null stays null. */
    public static String redact(String text, Collection<String> values) {
        if (text == null || values == null || values.isEmpty()) {
            return text;
        }
        List<String> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        String result = text;
        for (String value : ordered) {
            if (!value.isEmpty()) {
                result = result.replace(value, MARKER);
            }
        }
        return result;
    }

    /** Remember values to scrub from every later log line. Per-tenant masked fields must never appear in clear logs
     *  regardless of their length (E12-F2-S2). */
    public static void register(Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                REGISTRY.put(value, Boolean.TRUE);
            }
        }
    }

    /** The text with every registered value replaced by {@link #MARKER}; null stays null. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty() || REGISTRY.isEmpty()) {
            return text;
        }
        List<String> registered;
        synchronized (REGISTRY) {
            registered = new ArrayList<>(REGISTRY.keySet());
        }
        List<String> present = new ArrayList<>();
        for (String value : registered) {
            if (text.contains(value)) {
                present.add(value);
            }
        }
        return present.isEmpty() ? text : redact(text, present);
    }
}
