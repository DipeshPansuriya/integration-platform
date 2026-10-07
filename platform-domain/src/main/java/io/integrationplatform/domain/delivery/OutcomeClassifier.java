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
 */
public final class OutcomeClassifier {

    /** Refusals and validation errors: final, whatever a target's error dictionary says. */
    private static final Set<Integer> ALWAYS_PERMANENT = Set.of(400, 401, 403, 422);

    private static final Set<Integer> TRANSIENT = Set.of(429, 503);

    private OutcomeClassifier() {
    }

    /** The default class of {@code status} (no error dictionary). */
    public static OutcomeClass ofHttpStatus(int status, boolean targetDeclaresIdempotentReceive) {
        return ofHttpStatus(status, targetDeclaresIdempotentReceive, Map.of());
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
