package io.integrationplatform.ingest.internal.outbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ADR-005 Decision 11: each outbox row carries a bucket number - the record key modulo 64. The record key arrives
 * as text (ADR-011 Decision 7 builds it as SHA-256 of tenant, interface, business key), so the bucket is the low
 * six bits of SHA-256(record key): deterministic (the same key always lands in the same bucket - one publisher per
 * bucket), uniform, and always within the {@code CHECK (key_bucket BETWEEN 0 AND 63)} of {@code V2_0_1__outbox.sql}.
 */
final class KeyBuckets {

    /** The fixed register size - changing it changes every stored row's bucket (never do it silently). */
    static final int BUCKET_COUNT = 64;

    private KeyBuckets() {
    }

    /** The bucket of one record key, 0..63. */
    static int of(String recordKey) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(recordKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by ADR-005 Decision 11", impossible);
        }
        return digest[0] & (BUCKET_COUNT - 1);
    }
}
