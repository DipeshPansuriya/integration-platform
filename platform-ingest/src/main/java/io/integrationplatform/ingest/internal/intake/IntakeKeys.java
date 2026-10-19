package io.integrationplatform.ingest.internal.intake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/** T115 pure intake keys: the Idempotency-Key request hash (T-187) and the per-key ordering plan (T-196). */
final class IntakeKeys {

    private IntakeKeys() {
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    /** Order-sensitive hash of the request; every part is length-prefixed so no two splits collide. */
    static String requestHash(String interfaceCode, String sourceContractVersion, List<String> records) {
        StringBuilder canonical = new StringBuilder();
        part(canonical, interfaceCode);
        part(canonical, sourceContractVersion);
        records.forEach(record -> part(canonical, record));
        return sha256(canonical.toString());
    }

    private static void part(StringBuilder canonical, String value) {
        String text = value == null ? "" : value;
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    /** Distinct keys with their counts, in key-hash order, so concurrent intakes lock rows in one order. */
    static Map<String, Integer> orderingPlan(List<String> keys) {
        Map<String, Integer> counts = new HashMap<>();
        keys.forEach(key -> counts.merge(key, 1, Integer::sum));
        List<String> sorted = new ArrayList<>(counts.keySet());
        sorted.sort((x, y) -> sha256(x).compareTo(sha256(y)));
        Map<String, Integer> plan = new LinkedHashMap<>();
        sorted.forEach(key -> plan.put(key, counts.get(key)));
        return plan;
    }

    /** PER_KEY is the column default; only an explicit NONE skips the ordering step. */
    static boolean ordered(String ordering) {
        return !"NONE".equals(ordering);
    }
}
