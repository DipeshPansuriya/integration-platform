package io.integrationplatform.domain.delivery;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ONE place an HTTP answer of a target becomes an {@link OutcomeClass} (ADR-010 section 5, default table; T-100).
 * Every send adapter (E10) classifies through here; no adapter maps a status itself.
 *
 * <ul>
 *   <li>2xx - {@code SUCCESS};</li>
 *   <li>429, 503 - {@code TRANSIENT} (the target said "later"; {@code Retry-After} is the caller's to honour);</li>
 *   <li>408, 500, 502, 504 and any other 5xx - the request may have been processed: {@code AMBIGUOUS}, or
 *       {@code TRANSIENT} when the target declares idempotent receive (ADR-011);</li>
 *   <li>every other status (4xx, and an unfollowed 1xx/3xx) - {@code PERMANENT}.</li>
 * </ul>
 *
 * A target's error dictionary may re-class a status for that target, but <b>never</b> 401, 403 or a validation error
 * (400, 422): those are {@code PERMANENT} whatever the dictionary says, so no layer of the platform ever retries them
 * (ADR-007 Decision 4, ADR-010 section 5, R13).
 *
 * <p>E5 T-185 (ADR-007 Decision 7 rule 5, S-d): the two decryption failures of a target credential are classed here
 * too ({@link #ofCredentialFault}) - neither is an HTTP answer, both are found before any call leaves the process.
 */
public final class OutcomeClassifier {

    /** Refusals and validation errors: final, whatever a target's error dictionary says. */
    private static final Set<Integer> ALWAYS_PERMANENT = Set.of(400, 401, 403, 422);

    private static final Set<Integer> TRANSIENT = Set.of(429, 503);

    private OutcomeClassifier() {
    }

    /** The two ways a target credential fails to open at send time (ADR-007 Decision 7 rule 5) - a closed register. */
    public enum CredentialFault {

        /**
         * The row names a key-encryption-key id this instance was deployed without: a deployment fault, fixable
         * without touching the row. No call, no attempt row, no attempt used up; the delivery waits.
         */
        KEY_UNAVAILABLE,

        /** The authentication tag does not match: the ciphertext, its associated data or the key is wrong. */
        TAG_MISMATCH
    }

    /**
     * The class of a target-credential decryption failure: an unknown key id is {@code TRANSIENT} (another instance,
     * or this one redeployed with the key, sends it); a tag mismatch is {@code PERMANENT} (tampering or a copied row
     * never heals by waiting - dead-lettered with the reason "target credential unreadable").
     */
    public static OutcomeClass ofCredentialFault(CredentialFault fault) {
        return switch (Objects.requireNonNull(fault, "fault")) {
            case KEY_UNAVAILABLE -> OutcomeClass.TRANSIENT;
            case TAG_MISMATCH -> OutcomeClass.PERMANENT;
        };
    }

    /** The default class of {@code status} (no error dictionary). */
    public static OutcomeClass ofHttpStatus(int status, boolean targetDeclaresIdempotentReceive) {
        return ofHttpStatus(status, targetDeclaresIdempotentReceive, Map.of());
    }

    /**
     * The class of a send that cut its connection on the endpoint's timeout (E10, T-050): the request may have
     * been processed, so {@code AMBIGUOUS} - or {@code TRANSIENT} when the target declares idempotent receive,
     * the same register as the 408/5xx family. No adapter maps a timeout itself.
     */
    public static OutcomeClass ofTimeout(boolean targetDeclaresIdempotentReceive) {
        return targetDeclaresIdempotentReceive ? OutcomeClass.TRANSIENT : OutcomeClass.AMBIGUOUS;
    }

    /**
     * The class of a connect failure (unknown host, refused socket): nothing was sent, so the try may repeat -
     * {@code TRANSIENT}. (A failed TLS handshake does not repeat: the destination's identity cannot be verified,
     * which is the refusal family - the same register {@code OutboundHttpCheck} keeps for its TLS_FAILED.)
     */
    public static OutcomeClass ofConnectFailure() {
        return OutcomeClass.TRANSIENT;
    }

    /**
     * The class of a refused destination (the guard said no, or a TLS handshake cannot verify the destination's
     * identity): only the endpoint's configuration can fix it - {@code PERMANENT}. Nothing is ever sent, and
     * nothing is ever retried.
     */
    public static OutcomeClass ofDestinationRefusal() {
        return OutcomeClass.PERMANENT;
    }

    /**
     * The class of {@code status} for a target with this error dictionary.
     *
     * @param errorDictionary the target's per-status overrides; ignored for 400, 401, 403 and 422
     */
    public static OutcomeClass ofHttpStatus(int status, boolean targetDeclaresIdempotentReceive,
            Map<Integer, OutcomeClass> errorDictionary) {
        Objects.requireNonNull(errorDictionary, "errorDictionary");
        if (ALWAYS_PERMANENT.contains(status)) {
            return OutcomeClass.PERMANENT;
        }
        OutcomeClass override = errorDictionary.get(status);
        if (override != null) {
            return override;
        }
        if (status >= 200 && status < 300) {
            return OutcomeClass.SUCCESS;
        }
        if (TRANSIENT.contains(status)) {
            return OutcomeClass.TRANSIENT;
        }
        if (status == 408 || status >= 500 && status < 600) {
            return targetDeclaresIdempotentReceive ? OutcomeClass.TRANSIENT : OutcomeClass.AMBIGUOUS;
        }
        return OutcomeClass.PERMANENT;
    }
}
