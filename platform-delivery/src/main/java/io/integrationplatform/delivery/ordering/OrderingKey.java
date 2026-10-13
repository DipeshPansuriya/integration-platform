package io.integrationplatform.delivery.ordering;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The ONE derivation of the ordering scope's record key (E9, ADR-011 Decision 7, T-197): SHA-256 hex of the
 * canonical {@code tenant_id + "\u001F" + interface_id + "\u001F" + business_key} triple. Every lane that needs
 * to name an ordering scope calls this - the advisory lock of the one lock order (T-197) and the stage-2 claim
 * must always derive the SAME string for the same scope, or same-key transactions would serialize on different
 * advisory keys and the hold order would be lost.
 *
 * <p>The derivation is a pure function of the scope and never touches storage; the delivery repository derives
 * the lock key of a row's scope from the row's own columns, and the routing consumer is expected to derive the
 * same string from the delivery row it read.
 */
public final class OrderingKey {

    /** The field separator of the canonical triple - the unit separator, so a key cannot collide across fields. */
    static final String SEPARATOR = "\u001F";

    private OrderingKey() {
    }

    /**
     * The record key of one ordering scope, 64 hex characters (ADR-011 Decision 7: "the SHA-256 of tenant,
     * interface, business key"). {@code businessKey} may be any text - the separator is not legal in keys.
     */
    public static String recordKeyFor(UUID tenantId, UUID interfaceId, String businessKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(interfaceId, "interfaceId");
        Objects.requireNonNull(businessKey, "businessKey");
        String canonical = tenantId + SEPARATOR + interfaceId + SEPARATOR + businessKey;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by ADR-011 Decision 7", impossible);
        }
    }
}